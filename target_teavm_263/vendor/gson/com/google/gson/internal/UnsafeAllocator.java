/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson.internal;

import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Do sneaky things to allocate objects without invoking their constructors.
 *
 * @author Joel Leitch
 * @author Jesse Wilson
 */
public abstract class UnsafeAllocator {
  public abstract <T> T newInstance(Class<T> c) throws Exception;

  /**
   * Asserts that the class is instantiable. This check should have already occurred in {@link
   * ConstructorConstructor}; this check here acts as safeguard since trying to use Unsafe for
   * non-instantiable classes might crash the JVM on some devices.
   */
  private static void assertInstantiable(Class<?> c) {
    String exceptionMessage = ConstructorConstructor.checkInstantiable(c);
    if (exceptionMessage != null) {
      throw new AssertionError(
          "UnsafeAllocator is used for non-instantiable type: " + exceptionMessage);
    }
  }

  public static final UnsafeAllocator INSTANCE = create();

  private static UnsafeAllocator create() {
    // browser build: no Unsafe / serialization tricks — classes without a
    // default constructor cannot be reflectively allocated
    return new UnsafeAllocator() {
      @Override
      public <T> T newInstance(Class<T> c) {
        assertInstantiable(c);
        throw new UnsupportedOperationException(
            "Cannot allocate " + c + " without a default constructor in the browser build");
      }
    };
  }
}
