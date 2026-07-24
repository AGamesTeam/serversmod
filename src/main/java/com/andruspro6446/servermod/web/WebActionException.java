package com.andruspro6446.servermod.web;

// A user-facing error from a web panel action (e.g. "not enough money"), safe to show directly on the page.
public class WebActionException extends RuntimeException
{
    public WebActionException(String message)
    {
        super(message);
    }
}
