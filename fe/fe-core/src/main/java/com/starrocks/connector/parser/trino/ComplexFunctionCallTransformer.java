// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.parser.trino;

import com.google.common.collect.ImmutableList;
import com.starrocks.analysis.CastExpr;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.TimestampArithmeticExpr;
import com.starrocks.catalog.Type;

public class ComplexFunctionCallTransformer {
    public static Expr transform(String functionName, Expr... args) {
        if (functionName.equalsIgnoreCase("date_add")) {
            if (args.length == 3 && args[0] instanceof StringLiteral) {
                StringLiteral unit = (StringLiteral) args[0];
                Expr interval = args[1];
                Expr date = args[2];
                return new TimestampArithmeticExpr(functionName, date, interval,
                        unit.getStringValue());
            }
        } else if (functionName.equalsIgnoreCase("json_format")) {
            return new CastExpr(Type.VARCHAR, args[0]);
        } else if (functionName.equalsIgnoreCase("json_extract_scalar")) {
            return new CastExpr(Type.VARCHAR, new FunctionCallExpr("json_query",
                    ImmutableList.of(args[0], args[1])));
        }
        return null;
    }
}
