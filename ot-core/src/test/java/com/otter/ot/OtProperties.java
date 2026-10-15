package com.otter.ot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class OtProperties {

    @Provide
    Arbitrary<Generators.ApplicableOp> applicableOps() {
        return Generators.applicableOps();
    }

    @Provide
    Arbitrary<Generators.ConcurrentOps> concurrentOps() {
        return Generators.concurrentOps();
    }

    @Provide
    Arbitrary<Generators.SequentialOps> sequentialOps() {
        return Generators.sequentialOps();
    }

    @Provide
    Arbitrary<Generators.ConcurrentSequences> concurrentSequences() {
        return Generators.concurrentSequences();
    }

    @Property
    void tp1Convergence(@ForAll("concurrentOps") Generators.ConcurrentOps scenario) {
        String s = scenario.state();
        TransformResult t = Ot.transform(scenario.a(), scenario.b());

        String viaA = Ot.apply(Ot.apply(s, scenario.a()), t.bPrime());
        String viaB = Ot.apply(Ot.apply(s, scenario.b()), t.aPrime());

        assertThat(viaA).isEqualTo(viaB);
    }

    @Property
    void sequenceTp1Convergence(@ForAll("concurrentSequences") Generators.ConcurrentSequences scenario) {
        String s = scenario.state();
        SequenceTransformResult t = Ot.transform(scenario.a(), scenario.b());

        String viaA = Ot.apply(Ot.apply(s, scenario.a()), t.bPrime());
        String viaB = Ot.apply(Ot.apply(s, scenario.b()), t.aPrime());

        assertThat(viaA).isEqualTo(viaB);
    }

    @Property
    void compositionLaw(@ForAll("sequentialOps") Generators.SequentialOps scenario) {
        String s = scenario.state();
        assertThat(Ot.apply(s, Ot.compose(scenario.a(), scenario.b())))
                .isEqualTo(Ot.apply(Ot.apply(s, scenario.a()), scenario.b()));
    }

    @Property
    void noOpIsAbsorbing(@ForAll("applicableOps") Generators.ApplicableOp applicable) {
        Operation op = applicable.op();
        assertThat(Ot.transform(new NoOp(), op).aPrime()).isEqualTo(new NoOp());
        assertThat(Ot.transform(op, new NoOp()).bPrime()).isEqualTo(new NoOp());
    }

    @Property
    void applyNoOpIsIdentity(@ForAll("applicableOps") Generators.ApplicableOp applicable) {
        assertThat(Ot.apply(applicable.state(), new NoOp())).isEqualTo(applicable.state());
    }

    @Property
    void inBoundsOpNeverThrows(@ForAll("applicableOps") Generators.ApplicableOp applicable) {
        assertThatCode(() -> Ot.apply(applicable.state(), applicable.op()))
                .doesNotThrowAnyException();
    }

    @Property
    void invertingAnOperationRestoresTheState(@ForAll("applicableOps") Generators.ApplicableOp applicable) {
        String after = Ot.apply(applicable.state(), applicable.op());
        Operation inverse = Ot.invert(applicable.op(), applicable.state());
        assertThat(Ot.apply(after, inverse)).isEqualTo(applicable.state());
    }

    @Provide
    Arbitrary<SequenceCase> sequenceCases() {
        return Generators.documents()
                .flatMap(state -> Generators.sequenceValidFor(state).map(seq -> new SequenceCase(state, seq)));
    }

    record SequenceCase(String state, OperationSequence sequence) {
    }

    @Property
    void invertingASequenceRestoresTheState(@ForAll("sequenceCases") SequenceCase scenario) {
        String after = Ot.apply(scenario.state(), scenario.sequence());
        OperationSequence inverse = Ot.invert(scenario.sequence(), scenario.state());
        assertThat(Ot.apply(after, inverse)).isEqualTo(scenario.state());
    }
}
