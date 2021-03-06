package net.stargraph.core.serializer;

/*-
 * ==========================License-Start=============================
 * stargraph-core
 * --------------------------------------------------------------------
 * Copyright (C) 2017 Lambda^3
 * --------------------------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ==========================License-End===============================
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import net.stargraph.model.*;

/**
 * The standard Serializer
 */
public final class ObjectSerializer {

    public static ObjectMapper createMapper(KBId kbId) {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Fact.class, new FactSerializer(kbId));
        module.addDeserializer(Fact.class, new FactDeSerializer(kbId));
        module.addSerializer(PropertyEntity.class, new PropertySerializer(kbId));
        module.addDeserializer(PropertyEntity.class, new PropertyDeserializer(kbId));
        module.addSerializer(InstanceEntity.class, new InstanceSerializer(kbId));
        module.addDeserializer(InstanceEntity.class, new InstanceDeserializer(kbId));
        module.addSerializer(ClassEntity.class, new ClassSerializer(kbId));
        mapper.registerModule(module);
        return mapper;
    }
}
