/*
 * Copyright (c) 2022-2024 Axonibyte Innovations, LLC. All rights reserved.
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

import com.axonibyte.lib.wildcard.Pattern;
import com.axonibyte.lib.wildcard.PatternedMap;
import com.axonibyte.lib.wildcard.PatternedSet;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles WebSockets, primarily for the purposes of dispatching notifications.
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
@WebSocket public class WSHandler {
  
  // Concurrent because putAction() mutates this from application startup threads while
  // onMessage() reads it from Jetty's I/O threads; a plain TreeMap here is a data race.
  private static final Map<String, WSAction> actions =
      new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);
  private static final Deque<Entry<Session, JSONObject>> pending = new ArrayDeque<>();
  
  // so this would be like { Session, { "USER", [ usernameVar... ] }
  // note that PatternedSet is implicitly Set<String>
  private static final Map<Session, Map<Object, PatternedSet>> sessionContextMap = new HashMap<>();
  
  // so this would be like { "USER", { usernameVar, [ Session... ] } }
  // note that PatternedMap is implicitly Map<String, T>
  private static final Map<Object, PatternedMap<Set<Session>>> contextSessionMap = new HashMap<>();
  
  // the authenticated identity associated with each live session, as returned by the
  // configured WSConnectionAuthenticator
  private static final Map<Session, Object> sessionIdentityMap = new HashMap<>();

  private static Logger logger = LoggerFactory.getLogger(WSHandler.class);
  private static Thread instance = null;

  /**
   * The identity assigned to connections when no {@link WSConnectionAuthenticator}
   * has been configured.
   */
  public static final Object ANONYMOUS_IDENTITY = new Object();

  // Permissive by default so that existing consumers are unaffected by the
  // introduction of this hook. Applications that expose a WebSocket route to an
  // untrusted network are expected to install a real authenticator; see
  // WSConnectionAuthenticator.
  private static WSConnectionAuthenticator connectionAuthenticator =
      session -> ANONYMOUS_IDENTITY;

  /**
   * Installs the {@link WSConnectionAuthenticator} used to vet incoming WebSocket
   * connections. Until this is called, all connections are accepted and assigned
   * {@link #ANONYMOUS_IDENTITY}.
   *
   * @param authenticator the authenticator to install; {@code null} restores the
   *        permissive default
   */
  public static synchronized void setConnectionAuthenticator(WSConnectionAuthenticator authenticator) {
    connectionAuthenticator = null == authenticator
        ? session -> ANONYMOUS_IDENTITY
        : authenticator;
  }

  /**
   * Retrieves the authenticated identity associated with a live session, as
   * determined by the configured {@link WSConnectionAuthenticator} when the
   * connection was established.
   *
   * @param session the {@link Session} in question
   * @return the identity, or {@code null} if the session is not (or is no longer)
   *         a live authenticated connection
   */
  public static Object identityOf(Session session) {
    synchronized(sessionIdentityMap) {
      return sessionIdentityMap.get(session);
    }
  }

  /**
   * Launches the dispatcher thread.
   *
   * @return {@code true} iff the dispatcher thread was launched after having
   *         been previously dead or {@code null}
   */
  public static synchronized boolean launchDispatcher() {
    if(null != instance) return false;
    instance = new Thread(new WSDispatcher());
    instance.setDaemon(true);
    instance.start();
    return true;
  }

  public static final Object HOST_CONTEXT = new Object();
  
  @OnWebSocketConnect public void onConnect(Session session) {
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();

    WSConnectionAuthenticator authenticator;
    synchronized(WSHandler.class) {
      authenticator = connectionAuthenticator;
    }

    Object identity;
    try {
      identity = authenticator.authenticate(session);
    } catch(Exception e) { // an authenticator that blows up must not admit the connection
      logger.error(
          "WebSocket authenticator threw {} for {}:{}; rejecting connection.",
          e.getClass().getSimpleName(),
          host,
          port);
      identity = null;
    }

    if(null == identity) {
      logger.warn("Rejected unauthenticated WebSocket connection from {}:{}", host, port);
      // Close before subscribing to anything, so a rejected peer never receives
      // dispatched traffic and never appears in the session maps.
      session.close(StatusCode.POLICY_VIOLATION, "unauthorized");
      return;
    }

    logger.info("WebSocket connect from {}:{}", host, port);

    synchronized(sessionIdentityMap) {
      sessionIdentityMap.put(session, identity);
    }

    subscribe(HOST_CONTEXT, session.getRemoteAddress().getHostString(), session);
  }
  
  @OnWebSocketClose public void onDisconnect(Session session, int statusCode, String reason) {
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    
    logger.info("WebSocket disconnect from {}:{}", host, port);

    boolean wasAuthenticated;
    synchronized(sessionIdentityMap) {
      wasAuthenticated = null != sessionIdentityMap.remove(session);
    }

    synchronized(sessionContextMap) {
      var sessionContexts = sessionContextMap.get(session);
      if(null != sessionContexts)
        for(var contextMap : sessionContexts.entrySet()) {
          var context = contextMap.getKey();
          var sessionMap = contextSessionMap.get(context);
          if(null == sessionMap) continue; // context already torn down
          for(var contextVal : contextMap.getValue()) {
            var sessions = sessionMap.get(contextVal);
            if(null == sessions) continue; // subscription already removed
            sessions.remove(session);
            if(sessions.isEmpty())
              sessionMap.remove(contextVal);
          }
          if(sessionMap.isEmpty())
            contextSessionMap.remove(context);
        }
      else if(wasAuthenticated)
        // Only noteworthy for a session that was actually admitted; connections
        // rejected in onConnect are never subscribed and land here by design.
        logger.error("Session disconnected, but was not in session map!");

      sessionContextMap.remove(session);
    }
  }
  
  @OnWebSocketMessage public void onMessage(Session session, String message) {
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    logger.info("WebSocket message received from {}:{}: \"{}\"", host, port, message.strip());
    
    try {
      JSONObject request = new JSONObject(message.strip());
      String action = request.getString("action");
      WSAction wsa = actions.get(action.toLowerCase());      
      if(null == wsa) {
        logger.warn("Bad action supplied: {}", action);
        return;
      }

      JSONObject response = wsa.onMessage(session, request);
      
      if(null == response) return;
      synchronized(pending) {
        pending.addLast(new SimpleEntry<>(session, response));
        pending.notifyAll();
      }
      
    } catch(Exception e) {
      logger.error("Exception of type {} caused by {}:{}.", e.getClass().getName(), host, port);
      e.printStackTrace();
    }
  }
  
  /**
   * Dispatches a message to all sessions.
   *
   * @param message the message to be sent
   */
  public static void dispatch(JSONObject message) {
    Objects.requireNonNull(message);
    
    Set<Session> sessions = null;
    synchronized(sessionContextMap) {
      sessions = new HashSet<>(sessionContextMap.keySet());
    }
    logger.info("Queueing message for broadcast: {}", message.toString());
    synchronized(pending) {
      for(var session : sessions)
        pending.addLast(new SimpleEntry<>(session, message));
      pending.notifyAll();
    }
  }
  
  /**
   * Dispatches a message to a session or set of sessions scoped by some
   * context and its respective value.
   *
   * @param context the context that the value is associated with
   * @param value the value associated with the context
   * @param message the message that needs to be sent
   */
  public static void dispatch(Object context, String value, JSONObject message) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(value);
    Objects.requireNonNull(message);
    
    PatternedMap<Set<Session>> sessionMap = null;
    synchronized(sessionContextMap) { // lock on sessionContextMap for consistency
      sessionMap = contextSessionMap.get(context);
    }

    if(null == sessionMap) {
      logger.error(
          "Session context {} was never registered.",
          context.toString());
    } else {
      Pattern pattern = new Pattern(value, false);
      Set<Session> sessions = new HashSet<>();
      for(var submap : sessionMap.get(pattern))
        sessions.addAll(submap);
    
      logger.info("Queueing message for dispatch: {}", message.toString());
      synchronized(pending) {
        for(var session : sessions)
          pending.addLast(new SimpleEntry<>(session, message));
        pending.notifyAll();
      }
    }
  }

  /**
   * Subscribes a session to channels scoped to a particular context.
   *
   * @param context the context
   * @param value the value of the context
   * @param session the websocket session
   */
  public static void subscribe(Object context, String value, Session session) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(value);
    Objects.requireNonNull(session);
    
    synchronized(sessionContextMap) {
      sessionContextMap.putIfAbsent(session, new HashMap<>());
      sessionContextMap.get(session).putIfAbsent(context, new PatternedSet());
      sessionContextMap.get(session).get(context).add(value);

      contextSessionMap.putIfAbsent(context, new PatternedMap<>());
      contextSessionMap.get(context).putIfAbsent(value, new HashSet<>());
      contextSessionMap.get(context).get(value).add(session);
    }
  }

  /**
   * Unsubscribes a session from a particular channel (defined by context).
   *
   * @param context the context
   * @param value the value of the context
   * @param session the websocket session
   */
  public static void unsubscribe(Object context, String value, Session session) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(value);
    Objects.requireNonNull(session);
    
    Pattern pattern = new Pattern(value, false);

    synchronized(sessionContextMap) {
      if(sessionContextMap.containsKey(session)
          && sessionContextMap.get(session).containsKey(context)
          && sessionContextMap.get(session).get(context).remove(pattern)
          && sessionContextMap.get(session).get(context).isEmpty()) {
        sessionContextMap.get(session).remove(context);
        if(sessionContextMap.get(session).isEmpty())
          sessionContextMap.remove(session);
      }
      
      if(contextSessionMap.containsKey(context)) {
        var contextValItr = contextSessionMap.get(context).get(pattern).iterator();
        while(contextValItr.hasNext()) {
          var contextVal = contextValItr.next();
          contextVal.remove(session);
          if(contextVal.isEmpty())
            contextValItr.remove();
        }
        if(contextSessionMap.get(context).isEmpty())
          contextSessionMap.remove(context);
      }
    }
  }

  /**
   * Establishes a {@link WSAction} that can be used to process certain incoming
   * WebSocket messages.
   *
   * @param the {@link WSAction} to insert into the workflow
   */
  public static void putAction(WSAction action) {
    Objects.requireNonNull(action);
    
    StringBuilder sb = new StringBuilder();
    for(String a : action.getActions()) {
      if(!sb.isEmpty()) sb.append(", ");
      sb.append(a);
      actions.put(a.toLowerCase(), action);
    }
    logger.info("Registering WebSocket actions: {}", sb.toString());
  }

  private static class WSDispatcher implements Runnable {

    @Override public void run() {
      try {
        while(!instance.isInterrupted()) {
          Entry<Session, JSONObject> entry = null;

          synchronized(pending) {
            while(pending.isEmpty()) pending.wait();
            entry = pending.pop();
            pending.notifyAll();
          }

          try {
            logger.debug(
                "Sending message to {}: \"{}\"",
                entry.getKey().getRemote().getInetSocketAddress().getHostString(),
                entry.getValue().toString());
            entry.getKey().getRemote().sendString(entry.getValue().toString());
          } catch(IOException e) {
            logger.error(
                "Some error occurred whilst processing the outgoing message: {}",
                null == e.getMessage() ? "no further info available" : e.getMessage());
          }
        }
      } catch(InterruptedException e) { }
    }
    
  }
  
}

