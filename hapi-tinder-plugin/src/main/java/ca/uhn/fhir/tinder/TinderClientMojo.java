package ca.uhn.fhir.tinder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.ParseException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.dstu.resource.Conformance;
import ca.uhn.fhir.model.dstu.resource.Conformance.Rest;
import ca.uhn.fhir.model.dstu.resource.Conformance.RestResource;
import ca.uhn.fhir.model.dstu.resource.Profile;
import ca.uhn.fhir.model.dstu.valueset.RestfulConformanceModeEnum;
import ca.uhn.fhir.rest.client.IRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.IBasicClient;
import ca.uhn.fhir.tinder.model.Resource;
import ca.uhn.fhir.tinder.model.RestResourceTm;
import ca.uhn.fhir.tinder.model.SearchParameter;

@Mojo(name = "generate-client", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class TinderClientMojo extends AbstractMojo {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(TinderClientMojo.class);

	@Parameter(required = true)
	private String serverBaseHref;

	@Parameter(required = true)
	private String clientClassName;

	@Parameter(required = true, defaultValue = "${project.build.directory}/generated-sources/tinder")
	private File targetDirectory;

	@Parameter(required = true, defaultValue = "false")
	private boolean generateSearchForAllParams;

	private List<RestResourceTm> myResources = new ArrayList<RestResourceTm>();
	private String myPackageBase;
	private File myDirectoryBase;
	private String myClientClassSimpleName;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		determinePaths();

		//
		// try {
		// ProfileParser pp = new ProfileParser();
		// Profile prof=(Profile) new FhirContext(Profile.class).newXmlParser().parseResource(IOUtils.toString(new FileReader("src/test/resources/profile.xml")));
		// pp.parseSingleProfile(prof, "http://foo");
		// File resourceDir = new File(myDirectoryBase, "resource");
		// resourceDir.mkdirs();
		// pp.writeAll(resourceDir, myPackageBase);
		// } catch (Exception e) {
		// throw new MojoFailureException("Failed to load resource profile: ",e);
		// }
		// if (true) {
		// return;
		// }

		FhirContext ctx = new FhirContext(Conformance.class);
		IRestfulClientFactory cFact = ctx.newRestfulClientFactory();
		IBasicClient client = cFact.newClient(IBasicClient.class, serverBaseHref);

		Conformance conformance = client.getServerConformanceStatement();

		if (conformance.getRest().size() != 1) {
			throw new MojoFailureException("Found " + conformance.getRest().size() + " rest definitions in Conformance resource. Need exactly 1.");
		}

		Rest rest = conformance.getRest().get(0);
		if (rest.getMode().getValueAsEnum() != RestfulConformanceModeEnum.SERVER) {
			throw new MojoFailureException("Conformance mode is not server, found: " + rest.getMode().getValue());
		}

		for (RestResource nextResource : rest.getResource()) {
			RestResourceTm nextTmResource = new RestResourceTm(nextResource);
			myResources.add(nextTmResource);

			Profile profile;
			try {
				ourLog.info("Loading Profile: {}", nextResource.getProfile().getResourceUrl());
				profile = (Profile) nextResource.getProfile().loadResource(client);
			} catch (IOException e) {
				throw new MojoFailureException("Failed to load resource profile: " + nextResource.getProfile().getReference().getValue(), e);
			}

			ProfileParser pp = new ProfileParser();
			Resource resourceModel;
			try {
				resourceModel = pp.parseSingleProfile(profile, nextResource.getProfile().getResourceUrl());
				File resourceDir = new File(myDirectoryBase, "resource");
				resourceDir.mkdirs();
				pp.writeAll(resourceDir, myPackageBase);
			} catch (Exception e) {
				throw new MojoFailureException("Failed to load resource profile: " + nextResource.getProfile().getReference().getValue(), e);
			}

			if (generateSearchForAllParams) {
				for (SearchParameter next : resourceModel.getSearchParameters()) {
					nextTmResource.getSearchParams().add(next);
				}
			}
		}

		try {
			write();
		} catch (IOException e) {
			throw new MojoFailureException("Failed to write", e);
		}

	}

	private void determinePaths() {
		myPackageBase = "";
		myDirectoryBase = targetDirectory;
		myClientClassSimpleName = clientClassName;
		if (clientClassName.lastIndexOf('.') > -1) {
			myPackageBase = clientClassName.substring(0, clientClassName.lastIndexOf('.'));
			myDirectoryBase = new File(myDirectoryBase, myPackageBase.replace(".", File.separatorChar + ""));
			myClientClassSimpleName = clientClassName.substring(clientClassName.lastIndexOf('.') + 1);
		}

		myDirectoryBase.mkdirs();
	}

	private void write() throws IOException {
		File file = new File(myDirectoryBase, myClientClassSimpleName + ".java");
		FileWriter w = new FileWriter(file, false);

		ourLog.info("Writing file: {}", file.getAbsolutePath());

		VelocityContext ctx = new VelocityContext();
		ctx.put("packageBase", myPackageBase);
		ctx.put("className", myClientClassSimpleName);
		ctx.put("resources", myResources);

		VelocityEngine v = new VelocityEngine();
		v.setProperty("resource.loader", "cp");
		v.setProperty("cp.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
		v.setProperty("runtime.references.strict", Boolean.TRUE);

		InputStream templateIs = ResourceGeneratorUsingSpreadsheet.class.getResourceAsStream("/vm/client.vm");
		InputStreamReader templateReader = new InputStreamReader(templateIs);
		v.evaluate(ctx, w, "", templateReader);

		w.close();
	}

	public static void main(String[] args) throws ParseException, IOException, MojoFailureException, MojoExecutionException {

		// PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(5000, TimeUnit.MILLISECONDS);
		// HttpClientBuilder builder = HttpClientBuilder.create();
		// builder.setConnectionManager(connectionManager);
		// CloseableHttpClient client = builder.build();
		//
		// HttpGet get = new HttpGet("http://fhir.healthintersections.com.au/open/metadata");
		// CloseableHttpResponse response = client.execute(get);
		//
		// String metadataString = EntityUtils.toString(response.getEntity());
		//
		// ourLog.info("Metadata String: {}", metadataString);

		// String metadataString = IOUtils.toString(new FileInputStream("src/test/resources/healthintersections-metadata.xml"));
		// Conformance conformance = new FhirContext(Conformance.class).newXmlParser().parseResource(Conformance.class, metadataString);

		TinderClientMojo mojo = new TinderClientMojo();
		mojo.clientClassName = "ca.uhn.test.ClientClass";
		mojo.targetDirectory = new File("target/gen");
		mojo.serverBaseHref = "http://fhir.healthintersections.com.au/open";
		mojo.execute();
	}

}