/*
 * Copyright (c) 2019-2023 Axonibyte Innovations, LLC
 *
 * Licensed under the Blue Oak Model License 1.0.0.
 * See LICENSE for the full terms.
 */
package com.axonibyte.lib.http.rest;

/**
 * An exception that can be thrown when some object that would not otherwise
 * qualify as a candidate for permission comparison is used nevertheless.
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public class BadPermException extends RuntimeException {

  /**
   * Instantiates this exception.
   *
   * @param obj the failed permission candidate
   */
  public BadPermException(Object obj) {
    super(
        String.format(
            "Absurd permission candidate: %1$s",
            obj.getClass().getName()));
  }
  
}
