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

import java.util.HashMap;
import java.util.Map;

import spark.Request;

/**
 * Assists in the retrieval of arguments from a query map.
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public class QParamTokenizer {

  private Request request = null;
  private Map<String, Boolean> requirements = new HashMap<>();

  /**
   * Instantiates this tokenizer form an HTTP request.
   *
   * @param request the Spark-provided HTTP request
   */
  public QParamTokenizer(Request request) {
    this.request = request;
  }

  /**
   * Flags the provided path as a potential parameter to be retrieved in the future.
   *
   * @param param the name of the parameter
   * @param required {@code true} if {@link QueryParamTokenizer::check} should throw
   *                 an exception when executed if the paramter does not exist in the request
   * @return this object
   */
  public QParamTokenizer tokenize(String param, boolean required) {
    requirements.put(param, required);
    return this;
  }

  /**
   * Determines whether or not the specified token is present
   * in the query map.
   *
   * @param token the needle in the haystack
   * @return {@code true} iff an argument matches the token parameter
   */
  public boolean has(String token) {
    return request.queryParams().contains(token);
  }

  /**
   * Retrieves an array of strings associated with the provided parameters.
   *
   * @param token the parameter associated with the values
   * @return an array containing arguments if they exist or {@code null} if the
   *         argument was nonexistent but optional
   * @throws {@link EndpointException} if the parameter was nonexistent but required
   */
  public String[] getArr(String token) throws EndpointException {
    if(!requirements.containsKey(token))
      throw new RuntimeException("Token not registered.");
    if(request.queryParams().contains(token))
      return request.queryParamsValues(token);
    if(requirements.get(token))
      throw new EndpointException(
          request,
          String.format(
              "Missing argument (%1$s).",
              token),
          400);
    return null;
  }

  /**
   * Retrieves the string value associated with the provided paramter.
   *
   * @param token the parameter associated with the value
   * @return the value or {@code null} if the value was nonexistent but optional
   * @throws {@link EndpointException} if the value was nonexistent but required
   */
  public String get(String token) throws EndpointException {
    if(!requirements.containsKey(token))
      throw new RuntimeException("Token not registered.");
    if(request.queryParams().contains(token))
      return request.queryParams(token);
    if(requirements.get(token))
      throw new EndpointException(
          request,
          String.format(
              "Missing argument (%1$s).",
              token),
          400);
    return null;
  }

  /**
   * Retrieves the value associated with the provided parameter.
   *
   * @param token the parameter associated with the value
   * @param clazz the desired return type
   * @return the value or {@code null} if the value was nonexistent but optional
   * @throws {@link EndpointException} if the value was nonexistent but required,
   *         or if the value could not be cast to the specified type
   */
  @SuppressWarnings("unchecked") public <T> T get(String token, Class<T> clazz) throws EndpointException {
    String raw = get(token);
    T value = null;
    if(null == raw) return null;
    try {
      if(clazz == Integer.class)
        value = (T)(Object)Integer.parseInt(raw);
      else if(clazz == Float.class || clazz == Double.class)
        value = (T)(Object)Double.parseDouble(raw);
      else if(clazz == Boolean.class)
        value = (T)(Object)Boolean.parseBoolean(raw);
    } catch(ClassCastException | NumberFormatException e) { }
    if(null == value)
      throw new EndpointException(
          request,
          String.format(
              "Malformed argument (%1$s).",
              token),
          400);
    return value;
  }

  /**
   * Checks to ensure that all required arguments are present.
   *
   * @return this object
   * @throws EndpointException if a required argument is missing
   */
  public QParamTokenizer check() throws EndpointException {
    var allowed = requirements.keySet();
    for(var needle : request.queryParams()) {
      if(!allowed.contains(needle))
        throw new EndpointException(
            request,
            String.format(
                "Unexpected argument (%1$s).",
                needle),
            400);
    }
    return this;
  }

}
