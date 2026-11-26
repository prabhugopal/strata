package io.strata.examples.services;

/** A collaborator: in a real app a client for a pricing service, a repository, an AI client… */
public interface Pricing {
    long price(String item);
}
