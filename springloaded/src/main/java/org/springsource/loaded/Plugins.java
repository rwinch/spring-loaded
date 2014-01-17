/*
 * Copyright 2010-2012 VMware and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springsource.loaded;

import org.springsource.loaded.agent.SpringLoadedPreProcessor;

/**
 * Manages plugin interactions between the user and the agent. Allows registration/removal/etc of plugins
 * 
 * <p>
 * tag: API
 * 
 * @author Andy Clement
 * @since 0.7.2
 */
public class Plugins {
	public static void registerGlobalPlugin(Plugin instance) {
		SpringLoadedPreProcessor.registerGlobalPlugin(instance);
	}

	public static void unregisterGlobalPlugin(Plugin instance) {
		SpringLoadedPreProcessor.unregisterGlobalPlugin(instance);
	}
}
