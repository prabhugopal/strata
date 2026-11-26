package io.strata.examples.shop;

import io.strata.annotation.Provides;

/** Prints instead of sending. Swap for a real mailer by providing another Mailer. */
@Provides
public class ConsoleMailer implements Mailer {
    @Override
    public void send(String text) {
        System.out.println("[mail] " + text);
    }
}
