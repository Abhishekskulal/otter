import { describe, expect, it } from 'vitest'
import { ClientDocument } from './clientDocument'
import { applySequence, transformSequence } from './engine'
import type { OperationSequence } from './operation'
import { randInt, randomOp, rng } from './randomOps'

interface Broadcast {
  ops: OperationSequence
  revision: number
  clientOpId: string
}

interface Submission {
  ops: OperationSequence
  baseRevision: number
  clientOpId: string
}

/** A single-document server that sequences submissions exactly as the server control loop does. */
function makeServer() {
  let content = ''
  let revision = 0
  const log: { revision: number; ops: OperationSequence }[] = []

  return {
    get content() {
      return content
    },
    submit(submission: Submission): Broadcast {
      let transformed = submission.ops
      for (const entry of log) {
        if (entry.revision > submission.baseRevision) {
          transformed = transformSequence(transformed, entry.ops).aPrime
        }
      }
      content = applySequence(content, transformed)
      revision += 1
      log.push({ revision, ops: transformed })
      return { ops: transformed, revision, clientOpId: submission.clientOpId }
    },
  }
}

function simulate(seed: number, clientCount: number, steps: number): void {
  const next = rng(seed)
  const server = makeServer()
  const serverInbox: Submission[] = []
  const clientInboxes: Broadcast[][] = []
  const clients: ClientDocument[] = []
  let opCounter = 0

  for (let id = 0; id < clientCount; id++) {
    const inbox: Broadcast[] = []
    clientInboxes.push(inbox)
    clients.push(
      new ClientDocument({
        send: (ops, baseRevision, clientOpId) => serverInbox.push({ ops, baseRevision, clientOpId }),
        onChange: () => {},
        newClientOpId: () => `c${id}-${opCounter++}`,
      }),
    )
  }

  const serverStep = () => {
    const submission = serverInbox.shift()
    if (!submission) return
    const broadcast = server.submit(submission)
    for (const inbox of clientInboxes) {
      inbox.push(broadcast)
    }
  }

  const deliverTo = (id: number) => {
    const broadcast = clientInboxes[id].shift()
    if (!broadcast) return
    clients[id].onServerOp(broadcast.ops, broadcast.revision, broadcast.clientOpId)
  }

  for (let step = 0; step < steps; step++) {
    const id = randInt(next, clientCount)
    switch (randInt(next, 3)) {
      case 0:
        clients[id].localEdit(randomOp(next, clients[id].content))
        break
      case 1:
        serverStep()
        break
      default:
        deliverTo(id)
    }
  }

  let safety = 1_000_000
  while (serverInbox.length > 0 || clientInboxes.some((inbox) => inbox.length > 0)) {
    while (serverInbox.length > 0) {
      serverStep()
    }
    for (let id = 0; id < clientCount; id++) {
      while (clientInboxes[id].length > 0) {
        deliverTo(id)
      }
    }
    if (--safety <= 0) {
      throw new Error(`simulation did not quiesce (seed ${seed})`)
    }
  }

  for (let id = 0; id < clientCount; id++) {
    expect(clients[id].content, `client ${id} diverged (seed ${seed})`).toBe(server.content)
  }
}

describe('client convergence under latency', () => {
  it('converges with two clients across many interleavings', () => {
    for (let seed = 1; seed <= 300; seed++) {
      simulate(seed, 2, 400)
    }
  })

  it('converges with three clients across many interleavings', () => {
    for (let seed = 1; seed <= 300; seed++) {
      simulate(seed * 7919, 3, 500)
    }
  })
})
