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
import java.security.ProtectionDomain;

import org.springsource.loaded.agent.ReloadDecision;



/**
 * Plugins implementing this interface are allowed to participate in determining whether a type should be made reloadable.
 * 
 * @author Andy Clement
 * @since 0.7.1
 */
public interface IsReloadableTypePlugin extends Plugin {

	/**
	 * @param typename slashed type name (e.g. java/lang/String)
	 * @param protectionDomain
	 * @param bytes the classfile data
	 */
	ReloadDecision shouldBeMadeReloadable(String typename, ProtectionDomain protectionDomain, byte[] bytes);

}
