package io.kcg.sir.lowering.api;

public interface LoweredModel {

    LoweredIrVersion irVersion();

    String targetId();

    String softwareName();
}
