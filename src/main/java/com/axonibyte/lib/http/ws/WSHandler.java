/*
 * Copyright (c) 2022-2023 Axonibyte Innovations, LLC. All rights reserved.
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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

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
  private static final Map<Session, Map<Object, Set<Object>>> sessionCategoryMap = new ConcurrentHashMap<>();
  
  // so this would be like { "USER", { usernameVar, [ Session... ] } }
  private static final Map<Object, Map<Object, Set<Session>>> categorySessionMap = new ConcurrentHashMap<>();
  
  private static Logger logger = LoggerFactory.getLogger(WSHandler.class);
  private static Thread instance = null;

  public static final Object HOST_CATEGORY = new Object();
  
  @OnWebSocketConnect public void onConnect(Session session) {
    if(null == instance) {
      instance = new Thread(new WSDispatcher());
      instance.setDaemon(true);
      instance.start();
    }
    
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    logger.info("WebSocket connect from {}:{}", host, port);

    subscribe(HOST_CATEGORY, session.getRemoteAddress().getHostString(), session);
  }
  
  @OnWebSocketClose public void onDisconnect(Session session, int statusCode, String reason) {
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    
    logger.info("WebSocket disconnect from {}:{}", host, port);

    var sessionCategories = sessionCategoryMap.get(session);
    for(var categoryMap : sessionCategories.entrySet()) {
      var category = categoryMap.getKey();
      for(var categoryVal : categoryMap.getValue()) {
        var sessionMap = categorySessionMap.get(category);
        sessionMap.get(categoryVal).remove(session);
        if(sessionMap.get(categoryVal).isEmpty())
          sessionMap.remove(categoryVal);
      }
      if(categorySessionMap.get(category).isEmpty())
        categorySessionMap.remove(category);
    }
    
    sessionCategoryMap.remove(session);
  }
  
  @OnWebSocketMessage public void onMessage(Session session, String message) {
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    logger.info("WebSocket message received from {}:{}: \"{}\"", host, port, message.strip());
    
    try {
      JSONObject request = new JSONObject(message.strip());
      String action = request.getString("action");
      WSAction wsa = actions.get(request.getString("action"));
      
      if(null == action) {
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
    }
  }
  
  /**
   * Dispatches a message to all sessions.
   *
   * @param message the message to be sent
   */
  public static void dispatch(JSONObject message) {
    var sessions = sessionCategoryMap.keySet();
    logger.info("Queueing message for broadcast: {}", message.toString());
    synchronized(pending) {
      for(var session : sessions)
        pending.addLast(new SimpleEntry<>(session, message));
      pending.notifyAll();
    }
  }
  
  /**
   * Dispatches a message to a session or set of sessions scoped by some
   * category and its respective value.
   *
   * @param category the category that the value is associated with
   * @param value the value associated with the category
   * @param message the message that needs to be sent
   */
  public static void dispatch(Object category, Object value, JSONObject message) {
    var sessionMap = categorySessionMap.get(category);
    if(null == sessionMap) return;
    
    var sessions = sessionMap.get(value);
    if(null == sessions) return;
    
    logger.info("Queueing message for dispatch: {}", message.toString());
    synchronized(pending) {
      for(var session : sessions)
        pending.addLast(new SimpleEntry<>(session, message));
      pending.notifyAll();
    }
  }

  /**
   * Subscribes a session to channels scoped to particular categories.
   *
   * @param category the category
   * @param value the value of the category
   * @param session the websocket session
   */
  public static void subscribe(Object category, Object value, Session session) {
    sessionCategoryMap.putIfAbsent(session, new ConcurrentHashMap<>());
    sessionCategoryMap.get(session).putIfAbsent(category, new CopyOnWriteArraySet<>());
    sessionCategoryMap.get(session).get(category).add(value);

    categorySessionMap.putIfAbsent(category, new ConcurrentHashMap<>());
    categorySessionMap.get(category).putIfAbsent(value, new CopyOnWriteArraySet<>());
    categorySessionMap.get(category).get(value).add(session);
  }

  /**
   * Unsubscribes a session from a particular channel (defined by categories).
   *
   * @param category the category
   * @param value the value of the category
   * @param session the websocket session
   */
  public static void unsubscribe(Object category, Object value, Session session) {
    if(sessionCategoryMap.containsKey(session)
        && sessionCategoryMap.get(session).containsKey(category)
        && sessionCategoryMap.get(session).get(category).remove(value)
        && sessionCategoryMap.get(session).get(category).isEmpty()) {
      sessionCategoryMap.get(session).remove(category);
      if(sessionCategoryMap.get(session).isEmpty())
        sessionCategoryMap.remove(session);
    }

    if(categorySessionMap.containsKey(category)
        && categorySessionMap.get(category).containsKey(value)
        && categorySessionMap.get(category).get(value).remove(session)
        && categorySessionMap.get(category).get(value).isEmpty()) {
      categorySessionMap.get(category).remove(value);
      if(categorySessionMap.get(category).isEmpty())
        categorySessionMap.remove(category);
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
    actions.put(action.getAction(), action);
  }

  private static class WSDispatcher implements Runnable {

    @Override public void run() {
      try {
        while(!instance.isInterrupted()) {
          Entry<Session, JSONObject> entry = null;

          synchronized(pending) {
            while(pending.isEmpty()) pending.wait();
            entry = pending.pop();
          }
        
          try {
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

