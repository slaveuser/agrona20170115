/*
 * Copyright 2014 - 2016 Real Logic Ltd.
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
package org.agrona.concurrent.status;

/**
 * Indicates how far through an abstract task a component has progressed as a counter value.
 */
public interface ReadablePosition extends AutoCloseable
{
    /**
     * Identifier for this position.
     *
     * @return the identifier for this position.
     */
    int id();

    /**
     * Get the current position of a component with volatile semantics
     *
     * @return the current position of a component with volatile semantics
     */
    long getVolatile();

    void close();
}
