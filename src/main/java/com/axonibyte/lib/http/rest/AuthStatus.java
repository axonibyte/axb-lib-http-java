/*
 * Copyright (c) 2023 Axonibyte Innovations, LLC
 *
 * Licensed under the Blue Oak Model License 1.0.0.
 * See LICENSE for the full terms.
 */
package com.axonibyte.lib.http.rest;

/**
 * A container that reflects the set of permissions associated with a user
 * accessing the RESTful API.
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public interface AuthStatus {

  /**
   * Determines whether or not the user associated with this permission set has
   * permissions equivalent to the candidate provided.
   *
   * @param permission the permission candidate for comparison
   * @return {@code true} if the user is autorized to conduct actions equal to
   *         those that are authorized by the permission candidate
   * @throws BadPermException if the provided permission cannot be compared
   */
  public boolean is(Object permission);

  /**
   * Determines whther or not the user associated with this permission set has
   * permissions equivalent to or greater than the candidate provided.
   *
   * @param permission the permission candidate for comparison
   * @return {@code true} if the user is authorized to conduct actions equal to
   *         or more than what the permission candidate allows for
   * @throws BadPermException if the provided permission cannot be compared
   */
  public boolean atLeast(Object candidate);

  /**
   * Determines whether or not the user associated with this permission set has
   * permissions equivalent to or less than the candidate provided.
   *
   * @param permission the permission candidate for comparison
   * @return {@code true} if the user is authorized to conduct actions equal to
   *         or less than what the permission candidate allows for
   * @throws BadPermException if the provided permission cannot be compared
   */
  public boolean atMost(Object candidate);
  
}
