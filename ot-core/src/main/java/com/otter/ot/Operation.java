package com.otter.ot;

public sealed interface Operation permits Insert, Delete, NoOp {
}
