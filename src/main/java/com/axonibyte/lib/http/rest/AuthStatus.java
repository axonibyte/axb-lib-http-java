/*
 * Copyright (c) 2023 Axonibyte Innovations, LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
