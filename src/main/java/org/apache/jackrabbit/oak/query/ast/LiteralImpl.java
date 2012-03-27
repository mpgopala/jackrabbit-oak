/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.query.ast;

import org.apache.jackrabbit.oak.query.SQL2Parser;
import org.apache.jackrabbit.oak.query.CoreValue;

public class LiteralImpl extends StaticOperandImpl {

    private final CoreValue value;

    public LiteralImpl(CoreValue value) {
        this.value = value;
    }

    public CoreValue getLiteralValue() {
        return value;
    }

    @Override
    boolean accept(AstVisitor v) {
        return v.visit(this);
    }

    @Override
    public String toString() {
        switch (value.getType()) {
        case CoreValue.BINARY:
            return cast("BINARY");
        case CoreValue.BOOLEAN:
            return cast("BOOLEAN");
        case CoreValue.DATE:
            return cast("DATE");
        case CoreValue.DECIMAL:
            return cast("DECIMAL");
        case CoreValue.DOUBLE:
        case CoreValue.LONG:
            return value.getString();
        case CoreValue.NAME:
            return cast("NAME");
        case CoreValue.PATH:
            return cast("PATH");
        case CoreValue.REFERENCE:
            return cast("REFERENCE");
        case CoreValue.STRING:
            return escape();
        case CoreValue.URI:
            return cast("URI");
        case CoreValue.WEAKREFERENCE:
            return cast("WEAKREFERENCE");
        default:
            return escape();
        }
    }

    private String cast(String type) {
        return "CAST(" + escape() + " AS " + type + ')';
    }

    private String escape() {
        return SQL2Parser.escapeStringLiteral(value.getString());
    }

    @Override
    CoreValue currentValue() {
        return value;
    }

}
