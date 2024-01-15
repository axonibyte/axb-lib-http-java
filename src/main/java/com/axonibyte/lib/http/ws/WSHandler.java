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
import java.util.Set;
import java.util.TreeMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;

import org.eclipse.jetty.websocket.api.Session;
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
  
  private static final Map<String, WSAction> actions = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private static final Deque<Entry<Session, JSONObject>> pending = new ArrayDeque<>();
  
  // so this would be like { Session, { "USER", [ usernameVar... ] }
  // note that PatternedSet is implicitly Set<String>
  private static final Map<Session, Map<Object, PatternedSet>> sessionContextMap = new HashMap<>();
  
  // so this would be like { "USER", { usernameVar, [ Session... ] } }
  // note that PatternedMap is implicitly Map<String, T>
  private static final Map<Object, PatternedMap<Set<Session>>> contextSessionMap = new HashMap<>();
  
  private static Logger logger = LoggerFactory.getLogger(WSHandler.class);
  private static Thread instance = null;

  public static synchronized boolean launchDispatcher() {
    if(null == instance) return false;
    instance = new Thread(new WSDispatcher());
    instance.setDaemon(true);
    instance.start();
    return true;
  }

  public static final Object HOST_CONTEXT = new Object();
  
  @OnWebSocketConnect public void onConnect(Session session) {
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    logger.info("WebSocket connect from {}:{}", host, port);

    subscribe(HOST_CONTEXT, session.getRemoteAddress().getHostString(), session);
  }
  
  @OnWebSocketClose public void onDisconnect(Session session, int statusCode, String reason) {
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    
    logger.info("WebSocket disconnect from {}:{}", host, port);

    synchronized(sessionContextMap) {
      var sessionContexts = sessionContextMap.get(session);
      if(null != sessionContexts)
        for(var contextMap : sessionContexts.entrySet()) {
          var context = contextMap.getKey();
          for(var contextVal : contextMap.getValue()) {
            var sessionMap = contextSessionMap.get(context);
            sessionMap.get(contextVal).remove(session);
            if(sessionMap.get(contextVal).isEmpty())
              sessionMap.remove(contextVal);
          }
          if(contextSessionMap.get(context).isEmpty())
            contextSessionMap.remove(context);
        }
      else logger.error("Session disconnected, but was not in session map!");
      
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
    PatternedMap<Set<Session>> sessionMap = null;
    synchronized(sessionContextMap) { // lock on sessionContextMap for consistency
      sessionMap = contextSessionMap.get(context);
    }
    
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

  /**
   * Subscribes a session to channels scoped to a particular context.
   *
   * @param context the context
   * @param value the value of the context
   * @param session the websocket session
   */
  public static void subscribe(Object context, String value, Session session) {
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
    logger.info("Registering WebSocket action {}", action.getAction());
    actions.put(action.getAction().toLowerCase(), action);
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

