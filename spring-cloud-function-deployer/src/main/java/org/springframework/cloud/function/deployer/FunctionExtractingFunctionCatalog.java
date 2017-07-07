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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.common.io.Files;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.loader.thin.ArchiveUtils;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.thin.ThinJarAppDeployer;
import org.springframework.cloud.function.context.FunctionInspector;
import org.springframework.cloud.function.registry.FunctionCatalog;
import org.springframework.context.support.LiveBeansView;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.MethodInvoker;

import javax.security.cert.CertificateException;
import javax.security.cert.X509Certificate;

public class FunctionExtractingFunctionCatalog
		implements FunctionCatalog, FunctionInspector, DisposableBean {

	private static Log logger = LogFactory
			.getLog(FunctionExtractingFunctionCatalog.class);

	private ThinJarAppDeployer deployer;

	private Map<String, String> deployed = new LinkedHashMap<>();

	private Map<String, String> names = new LinkedHashMap<>();

	private Map<String, String> ids = new LinkedHashMap<>();

	public FunctionExtractingFunctionCatalog() {
		this("thin", "slim");
	}

	public FunctionExtractingFunctionCatalog(String name, String... profiles) {
		deployer = new ThinJarAppDeployer(name, profiles);
	}

	@Override
	public void destroy() throws Exception {
		for (String name : new HashSet<>(names.keySet())) {
			undeploy(name);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Consumer<T> lookupConsumer(String name) {
		return (Consumer<T>) lookup(name, "lookupConsumer");
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T, R> Function<T, R> lookupFunction(String name) {
		return (Function<T, R>) lookup(name, "lookupFunction");
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Supplier<T> lookupSupplier(String name) {
		return (Supplier<T>) lookup(name, "lookupSupplier");
	}

	@SuppressWarnings("unchecked")
	@Override
	public Set<String> getSupplierNames() {
		return (Set<String>) catalog("getSupplierNames");
	}

	@SuppressWarnings("unchecked")
	@Override
	public Set<String> getFunctionNames() {
		return (Set<String>) catalog("getFunctionNames");
	}

	@SuppressWarnings("unchecked")
	@Override
	public Set<String> getConsumerNames() {
		return (Set<String>) catalog("getConsumerNames");
	}

	@Override
	public boolean isMessage(String name) {
		return (Boolean) inspect(name, "isMessage");
	}

	@Override
	public Class<?> getInputType(String name) {
		return (Class<?>) inspect(name, "getInputType");
	}

	@Override
	public Class<?> getOutputType(String name) {
		return (Class<?>) inspect(name, "getOutputType");
	}

	@Override
	public Class<?> getInputWrapper(String name) {
		return (Class<?>) inspect(name, "getInputWrapper");
	}

	@Override
	public Class<?> getOutputWrapper(String name) {
		return (Class<?>) inspect(name, "getOutputWrapper");
	}

	@Override
	public Object convert(String name, String value) {
		return inspect(name, "convert");
	}

	@Override
	public String getName(Object function) {
		return (String) inspect(function, "getName");
	}


	private String downloadRemoteDependency(String path) throws Exception {
		logger.info("downloadRemoteDependency invoked with path=["+path+"]");

		File zipFileRecord = File.createTempFile("faas", "faas");
		File destinationDir = Paths.get(System.getProperty("user.home") + "/.m2/repository/").toFile();

		URI sourceUri = new URI(path);
		if ("http".equals(sourceUri.getScheme()) || "https".equals(sourceUri.getScheme())){
			logger.info("Downloading remote artifact from ["+path+"]");

			SSLContextBuilder builder = new SSLContextBuilder();
			builder.loadTrustMaterial(null, new TrustStrategy() {
				@Override
				public boolean isTrusted(java.security.cert.X509Certificate[] x509Certificates, String s) throws java.security.cert.CertificateException {
					return true;
				}

			});

			SSLConnectionSocketFactory sslSF = new SSLConnectionSocketFactory(builder.build(),
					SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

			CloseableHttpClient closeableHttpClient = HttpClients.custom().setSSLSocketFactory(sslSF).build(); //new HttpGet(path);
			HttpGet httpGet = new HttpGet(path);
			HttpResponse downloadResponse = closeableHttpClient.execute(httpGet);

			InputStream iStream = downloadResponse.getEntity().getContent();
			FileOutputStream oStream = new FileOutputStream(zipFileRecord);
			IOUtils.copy(iStream, oStream);
		} else {
			return path;
		}

		logger.info("Unzipping ["+zipFileRecord.getAbsolutePath()+"] to ["+destinationDir.getAbsolutePath()+"]");

		Unzipper.unzip(zipFileRecord.getAbsolutePath(), destinationDir.getAbsolutePath());

		return "maven://io.pivotal.faas.eip:hackday:0.0.1-SNAPSHOT";
	}

	public String deploy(String name, String pathArg, String... args) {

		String path = pathArg;
		try {
			path = downloadRemoteDependency(pathArg);
		}catch(Exception c){
			throw new RuntimeException("Failed to download remote dependency", c);
		}

		Resource resource = new FileSystemResource(
				ArchiveUtils.getArchiveRoot(ArchiveUtils.getArchive(path)));
		AppDefinition definition = new AppDefinition(resource.getFilename(),
				Collections.singletonMap(LiveBeansView.MBEAN_DOMAIN_PROPERTY_NAME,
						"functions." + name));
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.singletonMap(AppDeployer.GROUP_PROPERTY_KEY, "functions"),
				Arrays.asList(args));
		String id = this.deployer.deploy(request);
		this.deployed.put(id, path);
		this.names.put(name, id);
		this.ids.put(id, name);
		return id;
	}

	public DeployedArtifact undeploy(String name) {
		String id = this.names.get(name);
		if (id == null) {
			// TODO: Convert to 404
			throw new IllegalStateException("No such app");
		}
		this.deployer.undeploy(id);
		String path = this.deployed.remove(id);
		this.names.remove(name);
		this.ids.remove(id);
		return new DeployedArtifact(name, id, path);
	}

	private Object inspect(Object arg, String method) {
		if (logger.isDebugEnabled()) {
			logger.debug("Inspecting " + method);
		}
		return invoke(FunctionInspector.class, method, arg);
	}

	private Object lookup(String name, String method) {
		if (logger.isDebugEnabled()) {
			logger.debug("Looking up " + name + " with " + method);
		}
		return invoke(FunctionCatalog.class, method, name);
	}

	private Object catalog(String method) {
		if (logger.isDebugEnabled()) {
			logger.debug("Calling " + method);
		}
		return invoke(FunctionCatalog.class, method);
	}

	private Object invoke(Class<?> type, String method, Object... arg) {
		Set<Object> results = new LinkedHashSet<>();
		for (String id : this.deployed.keySet()) {
			Object catalog = this.deployer.getBean(id, type);
			if (catalog == null) {
				continue;
			}
			String name = this.ids.get(id);
			String prefix = name + "/";
			if (arg.length == 1) {
				if (arg[0] instanceof String) {
					String specific = arg[0].toString();
					if (specific.startsWith(prefix)) {
						arg[0] = specific.substring(prefix.length());
					}
					else {
						continue;
					}
				}
			}
			try {
				MethodInvoker invoker = new MethodInvoker();
				invoker.setTargetObject(catalog);
				invoker.setTargetMethod(method);
				invoker.setArguments(arg);
				invoker.prepare();
				Object result = invoker.invoke();
				if (result != null) {
					if (result instanceof Collection) {
						for (Object value : (Collection<?>) result) {
							results.add(prefix + value);
						}
					}
					else if (result instanceof String) {
						return prefix + result;
					}

					else {
						return result;
					}
				}
			}
			catch (Exception e) {
				throw new IllegalStateException("Cannot extract catalog", e);
			}
		}
		return arg.length > 0 ? null : results;
	}

	public Map<String, Object> deployed() {
		Map<String, Object> result = new LinkedHashMap<>();
		for (String name : this.names.keySet()) {
			String id = this.names.get(name);
			result.put(name, new DeployedArtifact(name, id, this.deployed.get(id)));
		}
		return result;
	}

}

class DeployedArtifact {

	private String name;
	private String id;
	private String path;

	public DeployedArtifact() {
	}

	public DeployedArtifact(String name, String id, String path) {
		this.name = name;
		this.id = id;
		this.path = path;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

}
