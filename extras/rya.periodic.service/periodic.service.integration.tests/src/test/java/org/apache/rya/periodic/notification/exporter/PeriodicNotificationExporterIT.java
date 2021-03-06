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
package org.apache.rya.periodic.notification.exporter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.rya.indexing.pcj.storage.PeriodicQueryResultStorage;
import org.apache.rya.kafka.base.KafkaITBase;
import org.apache.rya.periodic.notification.serialization.BindingSetSerDe;
import org.junit.Assert;
import org.junit.Test;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.query.BindingSet;
import org.openrdf.query.algebra.evaluation.QueryBindingSet;

public class PeriodicNotificationExporterIT extends KafkaITBase {

    private static final ValueFactory vf = new ValueFactoryImpl();
    
    @Test
    public void testExporter() throws InterruptedException {
        
        BlockingQueue<BindingSetRecord> records = new LinkedBlockingQueue<>();
        Properties props = createKafkaConfig();
        
        KafkaExporterExecutor exporter = new KafkaExporterExecutor(new KafkaProducer<String, BindingSet>(props), 1, records);
        exporter.start();
        
        QueryBindingSet bs1 = new QueryBindingSet();
        bs1.addBinding(PeriodicQueryResultStorage.PeriodicBinId, vf.createLiteral(1L));
        bs1.addBinding("name", vf.createURI("uri:Bob"));
        BindingSetRecord record1 = new BindingSetRecord(bs1, "topic1");
        
        QueryBindingSet bs2 = new QueryBindingSet();
        bs2.addBinding(PeriodicQueryResultStorage.PeriodicBinId, vf.createLiteral(2L));
        bs2.addBinding("name", vf.createURI("uri:Joe"));
        BindingSetRecord record2 = new BindingSetRecord(bs2, "topic2");
        
        records.add(record1);
        records.add(record2);
        
        Set<BindingSet> expected1 = new HashSet<>();
        expected1.add(bs1);
        Set<BindingSet> expected2 = new HashSet<>();
        expected2.add(bs2);
        
        Set<BindingSet> actual1 = getBindingSetsFromKafka("topic1");
        Set<BindingSet> actual2 = getBindingSetsFromKafka("topic2");
        
        Assert.assertEquals(expected1, actual1);
        Assert.assertEquals(expected2, actual2);
        
        exporter.stop();
        
    }
    
    
    private Properties createKafkaConfig() {
        Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "group0");
        props.setProperty(ConsumerConfig.CLIENT_ID_CONFIG, "consumer0");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, BindingSetSerDe.class.getName());
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BindingSetSerDe.class.getName());

        return props;
    }
    
    
    private KafkaConsumer<String, BindingSet> makeBindingSetConsumer(final String TopicName) {
        // setup consumer
        final Properties consumerProps = createKafkaConfig();
        final KafkaConsumer<String, BindingSet> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Arrays.asList(TopicName));
        return consumer;
    }
    
    private Set<BindingSet> getBindingSetsFromKafka(String topic) {
        KafkaConsumer<String, BindingSet> consumer = null;

        try {
            consumer = makeBindingSetConsumer(topic);
            ConsumerRecords<String, BindingSet> records = consumer.poll(5000);

            Set<BindingSet> bindingSets = new HashSet<>();
            records.forEach(x -> bindingSets.add(x.value()));

            return bindingSets;

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (consumer != null) {
                consumer.close();
            }
        }
    }
}
