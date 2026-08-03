/*
 * Copyright (c) 2022-2026 Axonibyte Innovations, LLC. All rights reserved.
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

import org.eclipse.jetty.websocket.api.Session;

/**
 * Decides whether a newly-established WebSocket connection should be allowed to
 * proceed, and if so, what identity it carries.
 *
 * <p>Implementations are invoked from {@link WSHandler#onConnect(Session)},
 * before the session is subscribed to any context. The {@link Session} exposes
 * {@code getUpgradeRequest()}, so an implementation may inspect request headers,
 * query parameters, cookies, or the negotiated subprotocol in order to reach a
 * decision.</p>
 *
 * <p>Returning an identity rather than a simple boolean lets downstream
 * {@link WSAction} implementations retrieve the authenticated identity via
 * {@link WSHandler#identityOf(Session)} and check it against claims made in the
 * message body. Without that, a client can assert any identity it likes in the
 * payload and nothing is able to contradict it.</p>
 *
 * <p>Note that the WebSocket handshake has already completed by the time this is
 * called; rejection therefore takes the form of closing the freshly-opened
 * session rather than refusing the upgrade. The session is closed before it is
 * subscribed to anything, so a rejected peer never receives dispatched traffic.</p>
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
@FunctionalInterface public interface WSConnectionAuthenticator {

  /**
   * Authenticates an incoming WebSocket connection.
   *
   * @param session the freshly-established {@link Session}
   * @return an opaque, non-{@code null} identity for the connection if it should
   *         be permitted; {@code null} to reject and close the connection
   */
  public Object authenticate(Session session);

}
