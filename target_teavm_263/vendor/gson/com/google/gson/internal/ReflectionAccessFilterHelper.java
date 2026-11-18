/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gson.internal;

import com.google.gson.ReflectionAccessFilter;
import com.google.gson.ReflectionAccessFilter.FilterResult;
import java.lang.reflect.AccessibleObject;
import java.util.List;

/** Gson 2.14 access filters with its unavailable-canAccess fallback selected at build time. */
public final class ReflectionAccessFilterHelper {
  private ReflectionAccessFilterHelper() {}

  public static boolean isJavaType(Class<?> type) {
    return isJavaType(type.getName());
  }

  private static boolean isJavaType(String name) {
    return name.startsWith("java.") || name.startsWith("javax.");
  }

  public static boolean isAndroidType(Class<?> type) {
    return isAndroidType(type.getName());
  }

  private static boolean isAndroidType(String name) {
    return name.startsWith("android.") || name.startsWith("androidx.") || isJavaType(name);
  }

  public static boolean isAnyPlatformType(Class<?> type) {
    String name = type.getName();
    return isAndroidType(name) || name.startsWith("kotlin.")
        || name.startsWith("kotlinx.") || name.startsWith("scala.");
  }

  public static FilterResult getFilterResult(List<ReflectionAccessFilter> filters, Class<?> type) {
    for (ReflectionAccessFilter filter : filters) {
      FilterResult result = filter.check(type);
      if (result != FilterResult.INDECISIVE) {
        return result;
      }
    }
    return FilterResult.ALLOW;
  }

  public static boolean canAccess(AccessibleObject object, Object receiver) {
    // ponytail: pinned TeaVM lacks canAccess and access enforcement, as in Gson's fallback.
    // Restore the upstream probe when the classlib implements that API.
    return true;
  }
}
