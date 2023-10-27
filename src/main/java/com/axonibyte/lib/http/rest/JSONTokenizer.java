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
package com.axonibyte.lib.http.rest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;

import org.json.JSONException;
import org.json.JSONObject;

import spark.Request;

/**
 * Assists in the retrieval of arguments from the body of a request.
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public class JSONTokenizer {

  private Request request = null;
  private JSONObject data = null;
  private Map<String, Entry<String[], Boolean>> requirements = new HashMap<>();

  /**
   * Instantiates this tokenizer from an HTTP request.
   *
   * @param request the Spark-provided HTTP request
   */
  public JSONTokenizer(Request request) throws EndpointException {
    try {
      String raw = request.body();
      if(null == raw || raw.isBlank())
        throw new EndpointException(request, "Empty request.", 400);
      this.data = new JSONObject(raw);
      this.request = request;
    } catch(JSONException e) {
      throw new EndpointException(request, "Malformed request.", 400);
    }
  }

  /**
   * Instantiates this tokenizer with custom data.
   *
   * @param request the Spark-provided HTTP request
   * @param data the JSON that needs to be parsed
   */
  public JSONTokenizer(Request request, JSONObject data) {
    this.data = data;
    this.request = request;
  }

  /**
   * Flags the provided path as a potential parameter to be retrieved in the future.
   *
   * @param path the parameter path, in JSON notation (without a preceding dot)
   * @param required {@code true} if {@link JSONTokenizer::check} should throw an exception
   *                 when executed if the parameter does not exist in the request body
   * @return this object
   */
  public JSONTokenizer tokenize(String path, boolean required) {
    String[] tokens = path.split("\\.");
    requirements.put(path, new SimpleEntry<>(tokens, required));
    return this;
  }

  /**
   * Retrieves the underlying data.
   *
   * @return the underlying JSON object representing the original request
   */
  public JSONObject getData() {
    return data;
  }

  /**
   * Determines whether or not the request body contains the provided parameter.
   *
   * @param token the parameter in question
   * @return {@code true} iff the parameter exists in the JSON body
   */
  public boolean has(String token) {
    try {
      return null != get(token);
    } catch(EndpointException | RuntimeException e) {
      return false;
    }
  }

  private Object get(String[] tokens, JSONObject json) throws ClassCastException {
    String token = tokens[0];
    if(token.isBlank()) throw new RuntimeException("Malformed token.");

    if(null == json) return null;
    
    if(tokens.length == 1) return json.opt(token);
    return get(
        Arrays.copyOfRange(tokens, 1, tokens.length),
        json.optJSONObject(token));
  }

  /**
   * Retrieves the value of the provided parameter. If it doesn't exist and it was
   * marked as a required parameter, throw an {@link EndpointException}. If it
   * doesn't exist and it was marked as an optional parameter, return {@code null}.
   *
   * @param token the path to the requested value
   * @return the value or {@code null} if it doesn't exist and no exception is thrown
   * @throws an {@link EndpointException} if the values doesn't exist but was required
   */
  public Object get(String token) throws EndpointException {
    Entry<String[], Boolean> entry = requirements.get(token);
    if(null == entry)
      throw new RuntimeException("Token not registered.");
    
    String[] arr = entry.getKey();
    boolean required = entry.getValue();

    Object value = get(arr, data);

    if(required && null == value)
      throw new EndpointException(
          request,
          String.format(
              "Missing argument (%1$s).",
              token),
          400);

    return value;
  }

  /**
   * Retrieves the string value associated with the provided parameter.
   *
   * @param token the path to the requested value
   * @return the value or {@code null} if the value was nonexistent but optional
   * @throws {@link EndpointException} if the value was nonexistent and required,
   *         or if the value could not be cast as a String
   */
  public String getString(String token) throws EndpointException {
    try {
      return (String)get(token);
    } catch(ClassCastException e) {
      throw new EndpointException(
          request,
          String.format(
              "Malformed argument (string: %1$s).",
              token),
          400,
          e);
    }
  }

  /**
   * Retrieves the {@link BigDecimal} value associated with the provided parameter.
   *
   * @param token the path to the requested value
   * @return the value or {@code null} if the value was nonexistent but optional
   * @throws {@link EndpointException} if the value was nonexistent and required,
   *         or if the value could not be cast as a {@link BigDecimal}
   */
  public BigDecimal getDecimal(String token) throws EndpointException {
    try {
      return (BigDecimal)get(token);
    } catch(ClassCastException e) {
      throw new EndpointException(
          request,
          String.format(
              "Malformed argument (decimal: %1$s).",
              token),
          400,
          e);
    }
  }

  /**
   * Retrieves the Integer value associated with the provided parameter.
   *
   * @param token the path to the requested value
   * @return the value or {@code null} if the value was nonexistent but optional
   * @throws {@link EndpointException} if the value was nonexistent and required,
   *         or if the value could not be cast as an Integer
   */
  public Integer getInt(String token) throws EndpointException {
    try {
      return (Integer)get(token);
    } catch(ClassCastException e) {
      throw new EndpointException(
          request,
          String.format(
              "Malformed argument (int: %1$s).",
              token),
          400,
          e);
    }
  }

  /**
   * Retrieves the Boolean value associated with the provided paramter.
   *
   * @param token the path to the requested value
   * @return the value or {@code null} if the value was nonexistent but optional
   * @throws {@link EndpointException} if the value was nonexistent but required,
   *         or if the value could not be cast as a Boolean
   */
  public Boolean getBool(String token) throws EndpointException {
    try {
      return (Boolean)get(token);
    } catch(ClassCastException e) {
      throw new EndpointException(
          request,
          String.format(
              "Malformed argument (bool: %1$s).",
              token),
          400,
          e);
    }
  }

  /**
   * Retrieves the UUID value associated with the provided parameter.
   *
   * @param token the path to the requested value
   * @return the value or {@code null} if the value was nonexistent but optional
   * @throws {@link EndpointException} if the value was nonexistent but required,
   *         or if the value could not be cast as a UUID
   */
  public UUID getUUID(String token) throws EndpointException {
    try {
      var value = get(token);
      return null == value ? null : UUID.fromString(get(token).toString());
    } catch(IllegalArgumentException | NullPointerException e) {
      throw new EndpointException(
          request,
          String.format(
              "Malformed argument (uuid: %1$s).",
              token),
          400,
          e);
    }
  }

  /**
   * Retrieves the timestamp value associated with the provided parameter.
   *
   * @param token the path to the requested value
   * @return the value or {@code null} if the value was nonexistent but optional
   * @throws {@link EndpointException} if the value was nonexistent but required,
   *         or if the value could not be parsed as a {@link Timestamp}
   */
  public Timestamp getTimestamp(String token) throws EndpointException {
    var value = getString(token);
    final SimpleDateFormat[] sdfArr = {
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS"),
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"),
        new SimpleDateFormat("yyyy-MM-dd HH:mm"),
        new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS a"),
        new SimpleDateFormat("yyyy-MM-dd hh:mm:ss a"),
        new SimpleDateFormat("yyyy-MM-dd hh:mm a"),
        new SimpleDateFormat("yyyy-MM-dd")
    };
    for(int i = 0; i < sdfArr.length; i++) {
      try {
        return new Timestamp(sdfArr[i].parse(value).getTime());
      } catch(ParseException e) { }
    }
    throw new EndpointException(
        request,
        String.format(
            "Malformed argument (timestamp: %1$s).",
            token),
        400);
  }
  
  private void check(JSONObject json, Set<String[]> tokens) throws EndpointException {
    for(String key : json.keySet()) {
      Set<String[]> matchingTokens = new HashSet<>();
      for(String[] tokenArr : tokens) {
        if(tokenArr[0].equalsIgnoreCase(key))
          matchingTokens.add(tokenArr);
      }
      if(matchingTokens.isEmpty()) throw new EndpointException(
          request,
          String.format(
              "Unexpected argument (%1$s).",
              key),
          400);
      Object obj = json.get(key);
      if(obj instanceof JSONObject) {
        Set<String[]> tails = new HashSet<>();
        for(String[] matchingTokenArr : matchingTokens) {
          if(matchingTokenArr.length == 1) continue;
          tails.add(
              Arrays.copyOfRange(matchingTokenArr, 1, matchingTokenArr.length));
        }
        check((JSONObject)obj, tails);
      }
    }
  }
  
  /**
   * Checks to ensure that all required arguments are present.
   *
   * @return this object
   * @throws EndpointException if a required argument is missing
   */
  public JSONTokenizer check() throws EndpointException {
    Set<String[]> tokens = new HashSet<>();
    for(var entry : requirements.entrySet())
      tokens.add(entry.getValue().getKey());
    check(data, tokens);
    return this;
  }
  
}
