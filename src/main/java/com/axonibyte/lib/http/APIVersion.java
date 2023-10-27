/*
 * Copyright (c) 2020-2023 Axonibyte Innovations, LLC. All rights reserved.
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
package com.axonibyte.lib.http;

/**
 * The version of the API.
 * 
 * @author Caleb L. Power
 */
public enum APIVersion {
  
  /**
   * Unknown version of the API
   */
  UNKNOWN_VERSION,
  
  /**
   * Major version 1 of the API
   */
  VERSION_1,
  
  /**
   * Major version 2 of the API
   */
  VERSION_2,
  
  /**
   * Major version 3 of the API
   */
  VERSION_3,
  
  /**
   * Major version 4 of the API
   */
  VERSION_4,
  
  /**
   * Major version 5 of the API
   */
  VERSION_5,
  
  /**
   * Major version 6 of the API
   */
  VERSION_6,
  
  /**
   * Major version 7 of the API
   */
  VERSION_7,
  
  /**
   * Major version 8 of the API
   */
  VERSION_8,
  
  /**
   * Major version 9 of the API
   */
  VERSION_9;
  
  /**
   * Determines the API version from some string
   * 
   * @param val the string value of the API
   * @return the appropriate APIVersion object
   */
  public static APIVersion fromString(String val) {
    try {
      int v = Integer.parseInt(
          val.length() > 1 && val.toLowerCase().charAt(0) == 'v'
              ? val.substring(1) : val);
      for(APIVersion ver : APIVersion.values())
        if(ver.ordinal() == v) return ver;
    } catch(NumberFormatException e) { }
    
    return UNKNOWN_VERSION;
  }

  /**
   * Adds the proper version to a route.
   */
  public String versionize(String route) {
    return String.format("/v%1$d%2$s%3$s",
        ordinal(),
        route.startsWith("/") ? "" : "/",
        route);
  }
  
}
