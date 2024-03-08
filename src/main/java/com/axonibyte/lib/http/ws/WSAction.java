/*
 * Copyright (c) 2023-2024 Axonibyte Innovations, LLC. All rights reserved.
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
package com.axonibyte.lib.http.ws;

import java.util.Objects;

import org.eclipse.jetty.websocket.api.Session;
import org.json.JSONObject;

/**
 * An action to be taken when the websocket receives a certain sort of message.
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public abstract class WSAction {

  private String[] actions = null;

  /**
   * Instantiates the WebSocket action.
   *
   * @param action the name(s) of the action(s) to be invoked by clients; at
   *        least one action name must be specified
   */
  public WSAction(String... actions) {
    Objects.requireNonNull(actions);
    if(0 == actions.length)
      throw new IllegalArgumentException("at least one action name must be specified");
    this.actions = actions;
  }

  /**
   * Retrieves the names of the actions to be invoked by clients.
   *
   * @return the action names
   */
  public String[] getActions() {
    return actions;
  }

  /**
   * Performs some operation when a message destined for this action is received.
   *
   * @param the user's WebSocket session
   * @param message the content of the message sent by the user
   * @return a JSONObject containing the response if there is one or
   *         {@code null} if there shouldn't be a response
   */
  public abstract JSONObject onMessage(Session session, JSONObject message);
  
}
