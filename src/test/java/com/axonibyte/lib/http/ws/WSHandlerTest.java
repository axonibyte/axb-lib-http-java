/*
 * Copyright (c) 2026 Axonibyte Innovations, LLC. All rights reserved.
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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Tests WebSocket connection authentication.
 *
 * <p>The WebSocket handshake completes before {@code onConnect} is reached, so
 * rejection takes the form of closing the freshly-opened session. The property that
 * matters is that a rejected peer is closed <em>before</em> being subscribed to any
 * context -- otherwise it would receive dispatched traffic in the window between
 * being admitted and being closed.</p>
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public class WSHandlerTest {

  /**
   * Minimal hand-rolled Session stub. Jetty's Session is a wide interface and only a
   * few members are exercised here, so a stub is clearer than a mocking framework and
   * keeps the test free of PowerMock.
   */
  private static final class StubSession implements Session {

    private final InetSocketAddress remote;
    private final List<Integer> closeCodes = new ArrayList<>();
    private final List<String> closeReasons = new ArrayList<>();

    private StubSession(String host, int port) {
      this.remote = InetSocketAddress.createUnresolved(host, port);
    }

    @Override public InetSocketAddress getRemoteAddress() {
      return remote;
    }

    @Override public void close(int statusCode, String reason) {
      closeCodes.add(statusCode);
      closeReasons.add(reason);
    }

    private boolean wasClosed() {
      return !closeCodes.isEmpty();
    }

    // --- unused members -------------------------------------------------------

    @Override public void close() { }
    @Override public void close(org.eclipse.jetty.websocket.api.CloseStatus closeStatus) { }
    @Override public void disconnect() { }
    @Override public long getIdleTimeout() { return 0L; }
    @Override public InetSocketAddress getLocalAddress() { return null; }
    @Override public org.eclipse.jetty.websocket.api.WebSocketPolicy getPolicy() { return null; }
    @Override public String getProtocolVersion() { return null; }
    @Override public org.eclipse.jetty.websocket.api.RemoteEndpoint getRemote() { return null; }
    @Override public org.eclipse.jetty.websocket.api.UpgradeRequest getUpgradeRequest() { return null; }
    @Override public org.eclipse.jetty.websocket.api.UpgradeResponse getUpgradeResponse() { return null; }
    @Override public boolean isOpen() { return true; }
    @Override public boolean isSecure() { return false; }
    @Override public void setIdleTimeout(long ms) { }
    @Override public org.eclipse.jetty.websocket.api.SuspendToken suspend() { return null; }
  }

  @AfterMethod public void restoreDefaultAuthenticator() {
    // Static state on WSHandler leaks across tests otherwise.
    WSHandler.setConnectionAuthenticator(null);
  }

  @Test public void onConnect_withoutAuthenticator_admitsWithAnonymousIdentity() {
    var handler = new WSHandler();
    var session = new StubSession("10.0.0.5", 51000);

    handler.onConnect(session);

    Assert.assertFalse(session.wasClosed(), "default authenticator should admit");
    Assert.assertSame(WSHandler.identityOf(session), WSHandler.ANONYMOUS_IDENTITY);

    handler.onDisconnect(session, StatusCode.NORMAL, "done");
  }

  @Test public void onConnect_whenAuthenticatorRejects_closesWithPolicyViolation() {
    WSHandler.setConnectionAuthenticator(s -> null);

    var handler = new WSHandler();
    var session = new StubSession("10.0.0.6", 51001);

    handler.onConnect(session);

    Assert.assertTrue(session.wasClosed(), "rejected connection must be closed");
    Assert.assertEquals((int)session.closeCodes.get(0), StatusCode.POLICY_VIOLATION);
    Assert.assertNull(
        WSHandler.identityOf(session),
        "a rejected session must not be recorded as authenticated");
  }

  @Test public void onConnect_whenAuthenticatorRejects_doesNotSubscribeSession() {
    WSHandler.setConnectionAuthenticator(s -> null);

    var handler = new WSHandler();
    var rejected = new StubSession("10.0.0.7", 51002);
    handler.onConnect(rejected);

    // A rejected session must not be reachable by dispatch. If onConnect had
    // subscribed it before closing, this dispatch would target it.
    WSHandler.dispatch(new org.json.JSONObject().put("action", "probe"));

    Assert.assertNull(WSHandler.identityOf(rejected));
  }

  @Test public void onConnect_whenAuthenticatorAccepts_recordsReturnedIdentity() {
    Object identity = "profile-svc-manager";
    WSHandler.setConnectionAuthenticator(s -> identity);

    var handler = new WSHandler();
    var session = new StubSession("10.0.0.8", 51003);

    handler.onConnect(session);

    Assert.assertFalse(session.wasClosed());
    Assert.assertSame(
        WSHandler.identityOf(session),
        identity,
        "identity must be retrievable so actions can check payload claims against it");

    handler.onDisconnect(session, StatusCode.NORMAL, "done");
  }

  @Test public void onConnect_whenAuthenticatorThrows_rejectsRatherThanAdmits() {
    WSHandler.setConnectionAuthenticator(s -> {
      throw new IllegalStateException("key store unavailable");
    });

    var handler = new WSHandler();
    var session = new StubSession("10.0.0.9", 51004);

    handler.onConnect(session);

    // A failing authenticator must fail closed. Admitting on error would turn any
    // transient fault in the auth path into an open door.
    Assert.assertTrue(session.wasClosed(), "authenticator failure must reject");
    Assert.assertEquals((int)session.closeCodes.get(0), StatusCode.POLICY_VIOLATION);
    Assert.assertNull(WSHandler.identityOf(session));
  }

  @Test public void onDisconnect_clearsRecordedIdentity() {
    WSHandler.setConnectionAuthenticator(s -> "events-svc-manager");

    var handler = new WSHandler();
    var session = new StubSession("10.0.0.10", 51005);

    handler.onConnect(session);
    Assert.assertNotNull(WSHandler.identityOf(session));

    handler.onDisconnect(session, StatusCode.NORMAL, "done");
    Assert.assertNull(
        WSHandler.identityOf(session),
        "identity must not outlive the connection");
  }

  @Test public void onDisconnect_forNeverSubscribedSession_doesNotThrow() {
    WSHandler.setConnectionAuthenticator(s -> null);

    var handler = new WSHandler();
    var session = new StubSession("10.0.0.11", 51006);

    handler.onConnect(session);   // rejected, so never enters the session maps
    handler.onDisconnect(session, StatusCode.POLICY_VIOLATION, "unauthorized");
  }

}
