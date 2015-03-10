/**
 *
 * Copyright (c) 2006-2015, Speedment, Inc. All Rights Reserved.
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
package com.speedment.orm.platform.component;

import com.speedment.orm.core.Buildable;
import com.speedment.orm.core.manager.Manager;

/**
 *
 * @author Emil Forslund
 */
public interface ManagerComponent extends Component {

    @Override
    default Class<ManagerComponent> getComponentClass() {
        return ManagerComponent.class;
    }
    
    <E, B extends Buildable<E>> void put(Manager<E, B> manager);

    <E, B extends Buildable<E>, M extends Manager<E, B>> Manager<E, B> manager(Class<M> managerClass);

    <E, B extends Buildable<E>> Manager<E, B> managerOf(Class<E> entityClass);
}