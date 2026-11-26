package io.strata.gradle;

import org.gradle.api.provider.Property;

/** {@code strata { image { } }}: the container image built from the app's distribution. */
public abstract class ImageSpec {
    /** Image name, e.g. {@code counter} or {@code registry.example.com/team/counter}. Default: the project name. */
    public abstract Property<String> getName();
    public abstract Property<String> getTag();
    /** Base image with a JRE. */
    public abstract Property<String> getBase();
    /** The container CLI that builds it ({@code docker}, {@code podman}…). */
    public abstract Property<String> getBuilder();
}
