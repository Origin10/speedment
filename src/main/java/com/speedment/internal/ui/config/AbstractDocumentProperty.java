/**
 *
 * Copyright (c) 2006-2016, Speedment, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.speedment.internal.ui.config;

import com.speedment.config.Document;
import com.speedment.exception.SpeedmentException;
import com.speedment.internal.ui.config.trait.HasExpandedProperty;
import com.speedment.stream.MapStream;
import com.speedment.util.OptionalBoolean;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static java.util.stream.Collectors.toList;
import java.util.stream.Stream;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.Property;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import static javafx.collections.FXCollections.observableList;
import static javafx.collections.FXCollections.observableMap;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;

/**
 *
 * @author Emil Forslund
 */
public abstract class AbstractDocumentProperty implements DocumentProperty, HasExpandedProperty {
    
    /**
     * An observable map containing all the raw data that should be serialized
     * when the config model is saved. The config map can be modified by any
     * thread using the standard {@code Map} operations. It should never be
     * exposed as an {@code ObservableMap} as this would allow users to remove
     * the pre-installed listeners required for this class to work.
     */
    private final ObservableMap<String, Object> config;
    
    /**
     * An internal map of the properties that have been created by this class.
     * Two different properties must never be returned for the same key. A
     * property in this map must be configered to listen for changes in the
     * raw map before being inserted into the map.
     */
    private final transient Map<String, Property<?>> properties;
    
    /**
     * An internal map of the {@link DocumentProperty Document Properties} that 
     * have been created by this class. Once a list is associated with a 
     * particular key, it should never be removed. It should be configured to
     * listen to changes in the raw map before being inserted into this map.
     */
    private final transient Map<String, ObservableList<DocumentProperty>> documents;
    
    /**
     * An internal flag to indicate if events generated by the observable map
     * {@code config} should be ignored as the change is created internally.
     */
    private final transient EventMonitor monitor;

    /**
     * Wraps the specified raw map in an observable map so that any changes to
     * it can be observed by subclasses of this abstract class. The specified
     * parameter should never be used directly once passed to this constructor
     * as a parameter.
     * 
     * @param data  the raw data map
     */
    protected AbstractDocumentProperty(Map<String, Object> data) {
        this.config     = observableMap(data);
        this.properties = new ConcurrentHashMap<>();
        this.documents  = new ConcurrentHashMap<>();
        this.monitor    = new EventMonitor();
        
        this.config.addListener((MapChangeListener.Change<? extends String, ? extends Object> change) -> {
            
            // Make sure the event was not generated by an internal change to
            // the map.
            if (monitor.isEventsEnabled()) {
                
                // Removal events should not be processed.
                if (change.wasAdded()) {
                    final Object added = change.getValueAdded();
                    
                    // If the added value is a List, it might be a candidate for 
                    // a new child document 
                    if (added instanceof List<?>) {
                        @SuppressWarnings("unchecked")
                        final List<Object> addedList = (List<Object>) added;
                        
                        synchronized(documents) {
                            final ObservableList<DocumentProperty> l = documents.get(change.getKey());

                            // If no observable list have been created on
                            // the specified key yet and the proposed list
                            // can de considered a document, create a new
                            // list for it.
                            if (l == null) {
                                final List<DocumentProperty> children = addedList.stream()
                                    .filter(Map.class::isInstance)
                                    .map(obj -> (Map<String, Object>) obj)
                                    .map(obj -> createDocument(change.getKey(), obj))
                                    .collect(toList());

                                if (!children.isEmpty()) {
                                    documents.put(change.getKey(), prepareListOnKey(
                                        change.getKey(), 
                                        observableList(new CopyOnWriteArrayList<>(children))
                                    ));
                                }

                            // An observable list already exists on the
                            // specified key. Create document views of the
                            // added elements and insert them into the list.
                            } else {
                                addedList.stream()
                                    .filter(Map.class::isInstance)
                                    .map(obj -> (Map<String, Object>) obj)
                                    .map(obj -> createDocument(change.getKey(), obj))
                                    .forEachOrdered(l::add);
                            }
                        }
                        
                    // If it is not a list, it should be considered a property.
                    } else {
                        synchronized (properties) {
                            @SuppressWarnings("unchecked")
                            final Property<Object> p = (Property<Object>) properties.get(change.getKey());

                            // If there is already a property on the specified key, it's
                            // value should be updated to match the new value of the map
                            if (p != null) {
                                p.setValue(added);

                            // There is no property on the specified key since 
                            // before. Create it.
                            } else {
                                final Property<? extends Object> newProperty;
                                
                                if (added instanceof String) {
                                    newProperty = new SimpleStringProperty((String) added);
                                } else if (added instanceof Boolean) {
                                    newProperty = new SimpleBooleanProperty((Boolean) added);
                                } else if (added instanceof Integer) {
                                    newProperty = new SimpleIntegerProperty((Integer) added);
                                } else if (added instanceof Long) {
                                    newProperty = new SimpleLongProperty((Long) added);
                                } else if (added instanceof Double) {
                                    newProperty = new SimpleDoubleProperty((Double) added);
                                } else {
                                    newProperty = new SimpleObjectProperty(added);
                                }
                                
                                properties.put(change.getKey(), newProperty);
                            }
                        }
                    }
                }
            }
        });
    }

    @Override
    public final Map<String, Object> getData() {
        return config;
    }
    
    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(config.get(key));
    }
    
    @Override
    public OptionalBoolean getAsBoolean(String key) {
        return OptionalBoolean.ofNullable((Boolean) config.get(key));
    }

    @Override
    public OptionalLong getAsLong(String key) {
        final Long value = (Long) config.get(key);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    @Override
    public OptionalDouble getAsDouble(String key) {
        final Double value = (Double) config.get(key);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    @Override
    public OptionalInt getAsInt(String key) {
        final Integer value = (Integer) config.get(key);
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }
    
    @Override
    public Optional<String> getAsString(String key) {
        return get(key).map(String.class::cast);
    }

    @Override
    public void put(String key, Object value) {
        config.put(key, value);
    }
    
    @Override
    public final StringProperty stringPropertyOf(String key) {
        return (StringProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleStringProperty(getAsString(k).orElse(null))));
    }
    
    @Override
    public final IntegerProperty integerPropertyOf(String key) {
        return (IntegerProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleIntegerProperty(getAsInt(k).orElse(0))));
    }
    
    @Override
    public final LongProperty longPropertyOf(String key) {
        return (LongProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleLongProperty(getAsLong(k).orElse(0L))));
    }
    
    @Override
    public final DoubleProperty doublePropertyOf(String key) {
        return (DoubleProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleDoubleProperty(getAsDouble(k).orElse(0d))));
    }
    
    @Override
    public final BooleanProperty booleanPropertyOf(String key) {
        return (BooleanProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleBooleanProperty(getAsBoolean(k).orElse(false))));
    }
    
    @Override
    public final <T> ObjectProperty<T> objectPropertyOf(String key, Class<T> type) {
        return (ObjectProperty<T>) properties.computeIfAbsent(key, k -> prepare(k, new SimpleObjectProperty<>(type.cast(get(k).orElse(null)))));
    }
    
    /**
     * Returns an observable list of all the child documents under a specified
     * key. 
     * <p>
     * The implementation of the document is governed by the 
     * {@link #createDocument(java.lang.String, java.util.Map) createDocument} 
     * method. The specified {@code type} parameter must therefore match the
     * implementation created by 
     * {@link #createDocument(java.lang.String, java.util.Map) createDocument}.
     * 
     * @param <T>   the document type
     * @param key   the key to look at
     * @param type  the type of document to return
     * @return      an observable list of the documents under that key
     * 
     * @throws SpeedmentException  if the specified {@code type} is not the same
     *                             type as {@link #createDocument(java.lang.String, java.util.Map) createDocument}
     *                             generated.
     */
    public final <T extends DocumentProperty> ObservableList<T> observableListOf(String key, Class<T> type) throws SpeedmentException {
        try {
            @SuppressWarnings("unchecked")
            final ObservableList<T> list = (ObservableList<T>)
                documents.computeIfAbsent(key, k -> prepareListOnKey(k, observableList(new CopyOnWriteArrayList<>())));

            return list;
        } catch (ClassCastException ex) {
            throw new SpeedmentException(
                "Requested an ObservableList on key '" + key + 
                "' of a different type than 'createDocument' created.", ex
            );
        }
    }

    @Override
    public Stream<ObservableList<DocumentProperty>> childrenProperty() {
        return documents.values().stream();
    }

    protected DocumentProperty createDocument(String key, Map<String, Object> data) {
        return new DefaultDocumentProperty(this, data);
    } 
    
    @Override
    public final Stream<DocumentProperty> children() {
        return stream()
            .filterValue(obj -> obj instanceof List<?>)
            .mapValue(list -> (List<Object>) list)
            .flatMapValue(list -> list.stream())
            .filterValue(obj -> obj instanceof Map<?, ?>)
            .mapValue(map -> (Map<String, Object>) map)
            .mapValue((key, value) -> createDocument(key, value))
            .values();
    }
    
    private <T, P extends Property<T>> P prepare(String key, P property) {
        property.addListener((ObservableValue<? extends T> observable, T oldValue, T newValue) -> {
            monitor.runWithoutGeneratingEvents(() -> 
                config.put(key, newValue)
            );
        });
        
        return property;
    }
    
    private <T extends DocumentProperty> ObservableList<T> prepareListOnKey(String key, ObservableList<T> list) {
            
        monitor.runWithoutGeneratingEvents(() -> {
        
            config.computeIfAbsent(key, k -> {
                return new CopyOnWriteArrayList<>(list.stream()
                    .map(Document::getData)
                    .collect(toList())
                );
            });
            
            list.addListener((ListChangeListener.Change<? extends T> change) -> {
                monitor.runWithoutGeneratingEvents(() -> {
                    @SuppressWarnings("unchecked")
                    final List<Map<String, Object>> rawList = 
                        (List<Map<String, Object>>) config.get(key);

                    while (change.next()) {
                        if (change.wasAdded()) {
                            for (final T added : change.getAddedSubList()) {
                                rawList.add(added.getData());
                            }
                        }

                        if (change.wasRemoved()) {
                            for (final T removed : change.getRemoved()) {
                                rawList.remove(removed.getData());
                            }
                        }
                    }
                });
            });
        
        });

        return list;
    }
    
    private final static class EventMonitor {
        
        private final AtomicBoolean silence = new AtomicBoolean(false);
        
        public boolean isEventsEnabled() {
            return !silence.get();
        }

        public void runWithoutGeneratingEvents(Runnable runnable) {
            synchronized (silence) {
                silence.set(true);
                runnable.run();
                silence.set(false);
            }
        }
    }
}