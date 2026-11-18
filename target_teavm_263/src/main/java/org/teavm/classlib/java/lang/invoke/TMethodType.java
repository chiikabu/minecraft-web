/*
 *  Copyright 2017 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.classlib.java.lang.invoke;

import java.io.Serializable;

import org.teavm.classlib.java.lang.TClass;

public final class TMethodType implements Serializable {
    private final TClass<?> returnType;
    private final TClass<?>[] parameters;

    private TMethodType(TClass<?> returnType, TClass<?>[] parameters) {
        this.returnType = java.util.Objects.requireNonNull(returnType);
        this.parameters = parameters;
    }

    public static TMethodType methodType(TClass<?> returnType, TClass<?> parameterType) {
        java.util.Objects.requireNonNull(parameterType);
        if (parameterType.getName().equals("void")) throw new IllegalArgumentException("void parameter");
        return new TMethodType(returnType, new TClass<?>[] {parameterType});
    }

    public static TMethodType methodType(TClass<?> returnType) {
        return new TMethodType(returnType, new TClass<?>[0]);
    }

    public TClass<?> returnType() {
        return returnType;
    }

    public int parameterCount() {
        return parameters.length;
    }

    @Override public boolean equals(Object other) {
        return other instanceof TMethodType type && returnType == type.returnType
            && java.util.Arrays.equals(parameters, type.parameters);
    }

    @Override public int hashCode() {
        int hash = 31 + returnType.hashCode();
        for (TClass<?> parameter : parameters) hash = 31 * hash + parameter.hashCode();
        return hash;
    }
}
