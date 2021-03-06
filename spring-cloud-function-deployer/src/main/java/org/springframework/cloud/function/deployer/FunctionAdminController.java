/*
 * Copyright 2016-2017 the original author or authors.
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
package org.springframework.cloud.function.deployer;

import java.util.Collections;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Dave Syer
 *
 */
@RestController
@RequestMapping("/admin")
public class FunctionAdminController {

	private final FunctionExtractingFunctionCatalog deployer;

	@Autowired
	public FunctionAdminController(FunctionExtractingFunctionCatalog deployer) {
		this.deployer = deployer;
	}

	@PostMapping(path = "/{name}")
	public Map<String, Object> push(@PathVariable String name, @RequestParam String path)
			throws Exception {
		String id = deploy(name, path);
		return Collections.singletonMap("id", id);
	}

	@DeleteMapping(path = "/{name}")
	public Object undeploy(@PathVariable String name) throws Exception {
		return deployer.undeploy(name);
	}

	@GetMapping({ "", "/" })
	public Map<String, Object> deployed() {
		return deployer.deployed();
	}

	private String deploy(String name, String path, String... args) throws Exception {
		String deployed = deployer.deploy(name, path, args);
		return deployed;
	}
}
