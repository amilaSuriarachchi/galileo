/*
Copyright (c) 2014, Colorado State University
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

This software is provided by the copyright holders and contributors "as is" and
any express or implied warranties, including, but not limited to, the implied
warranties of merchantability and fitness for a particular purpose are
disclaimed. In no event shall the copyright holder or contributors be liable for
any direct, indirect, incidental, special, exemplary, or consequential damages
(including, but not limited to, procurement of substitute goods or services;
loss of use, data, or profits; or business interruption) however caused and on
any theory of liability, whether in contract, strict liability, or tort
(including negligence or otherwise) arising in any way out of the use of this
software, even if advised of the possibility of such damage.
*/

package galileo.test.graph;

import static org.junit.Assert.*;
import galileo.dataset.feature.Feature;
import galileo.graph.FeaturePath;

import galileo.graph.GraphException;
import galileo.query.Expression;
import galileo.query.Operation;
import galileo.query.Query;

import org.junit.Test;

public class FeaturePathQuery {

    @Test
    public void singleOperationPositiveQueries()
    throws GraphException {
        FeaturePath<String> fp = new FeaturePath<>("test path",
                new Feature("humidity", 32.3),
                new Feature("wind_speed", 5.0),
                new Feature("temperature", 274.8));

        Query q = new Query();
        q.addOperation(new Operation(
                    new Expression("<", new Feature("humidity", 64.8))));

        assertEquals("humidity less than", true, fp.satisfiesQuery(q));

        q = new Query();
        q.addOperation(new Operation(
                    new Expression("==", new Feature("wind_speed", 5.0))));

        assertEquals("wind equals", true, fp.satisfiesQuery(q));

        q = new Query();
        q.addOperation(new Operation(
                    new Expression("!=", new Feature("temperature", 1.0))));

        assertEquals("temperature not", true, fp.satisfiesQuery(q));
    }

    @Test
    public void singleOperationNegativeQueries()
    throws GraphException {
        FeaturePath<String> fp = new FeaturePath<>("test path",
                new Feature("humidity", 32.3),
                new Feature("wind_speed", 5.0),
                new Feature("temperature", 274.8));

        Query q = new Query();
        q.addOperation(new Operation(
                    new Expression(">", new Feature("humidity", 64.8))));

        assertEquals("humidity greater than", false, fp.satisfiesQuery(q));

        q = new Query();
        q.addOperation(new Operation(
                    new Expression("==", new Feature("wind_speed", 6.0))));

        assertEquals("wind equals", false, fp.satisfiesQuery(q));

        q = new Query();
        q.addOperation(new Operation(
                    new Expression("!=", new Feature("temperature", 274.8))));

        assertEquals("temperature not", false, fp.satisfiesQuery(q));
    }
}
