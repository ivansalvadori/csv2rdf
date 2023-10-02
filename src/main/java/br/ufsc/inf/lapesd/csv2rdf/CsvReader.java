package br.ufsc.inf.lapesd.csv2rdf;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.ontology.DatatypeProperty;
import org.apache.jena.ontology.Individual;
import org.apache.jena.ontology.ObjectProperty;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;


@Service
public class CsvReader {
	final Logger logger = LoggerFactory.getLogger(Main.class);

	//TODO: Analisar futuramente como esse link vai impactar na hora de subir o server na AWS
	private String resourceDomain = "http://example.com";

	@Value("${config.csvFilesFolder}")
	private String csvFilesFolder;

	@Value("${config.csvEncode}")
	private String csvEncode = "UTF-8";

	@Value("${config.csvSeparator}")
	private String csvSeparator = "COMMA";

	@Value("${config.rdfFormat}")
	private String rdfFormat = Lang.NTRIPLES.getName();
	
	@Value("${config.csvEncode}")
	private String rdfEncode = "UTF-8";

	@Value("${config.ontologyFormat}")
	String ontologyFormat = "N3";

	private Map<String, OntProperty> mapInverseProperties = new HashMap<>();
	private Model tempModel;
	private int individualsAddedToTempModel = 0;
	private int inMemoryModelSize = 1000;
	private int totalProcessedRecords = 0;
	private String currentFileId = UUID.randomUUID().toString();
	private String mappingFile = "mapping.jsonld";
	private String ontologyFile = "ontology.owl";
	private List<CsvReaderListener> listeners = new ArrayList<>();


	public InputStreamResource process() {
		this.tempModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_DL_MEM);
		this.resourceDomain = this.readResourceDomain();

		OntModel ontologyModel = createOntologyModel();
		this.createMapInverseProperties(ontologyModel);

		try {
			String csvFilesFolder = this.csvFilesFolder;
			Collection<File> files = FileUtils.listFiles(new File(csvFilesFolder), null, true);
			for (File file : files) {
				logger.info("reading " + file.getName());

				Reader in = new InputStreamReader(new FileInputStream(file.getPath()), this.csvEncode);

				Iterable<CSVRecord> records = null;

				if (csvSeparator.equalsIgnoreCase("COMMA")) {
					records = CSVFormat.DEFAULT.withFirstRecordAsHeader().withAllowMissingColumnNames().parse(in);
				} else if (csvSeparator.equalsIgnoreCase("TAB")) {
					records = CSVFormat.TDF.withFirstRecordAsHeader().parse(in);
				} else {
					records = CSVFormat.DEFAULT.withFirstRecordAsHeader().withAllowMissingColumnNames().withDelimiter(csvSeparator.toCharArray()[0]).parse(in);
				}

				for (CSVRecord record : records) {
					JsonObject mappingContext = createContextMapping();
					Individual resource;
					resource = createResourceModel(mappingContext, record);
					if (resource == null) {
						continue;
					}

					removeTBox(resource);

					this.tempModel.add(resource.getModel());
					this.individualsAddedToTempModel++;
					this.totalProcessedRecords++;

					for (CsvReaderListener listener : this.listeners) {
						listener.justRead(resource.getModel());
					}
				}
			}

			//TODO: tem que salvar aqui na verdade

			// converte o model para string
			String syntax = "NTRIPLE"; // also try "N-TRIPLE" and "TURTLE"
			StringWriter out = new StringWriter();
			this.tempModel.write(out, syntax);
			String result = out.toString();

			byte[] bytes = result.getBytes();
			InputStream inputStream = new ByteArrayInputStream(bytes);
			InputStreamResource inputStreamResource = new InputStreamResource(inputStream);

			for (CsvReaderListener listener : this.listeners) {
				listener.readProcessFinished();
			}
			logger.info("Process finished. Record(s) processed: " + totalProcessedRecords);

			return inputStreamResource;

		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private void removeTBox(Individual resource) {
		List<Statement> toRemove = new ArrayList<>();
		StmtIterator listStatements = resource.getModel().listStatements();
		while (listStatements.hasNext()) {
			Statement next = listStatements.next();
			if (!next.getSubject().getURI().startsWith(this.resourceDomain)) {
				toRemove.add(next);
			}
		}
		resource.getModel().remove(toRemove);
	}

	private JsonObject createContextMapping() {
		try (FileInputStream inputStream = FileUtils.openInputStream(new File(this.mappingFile))) {
			String mappingContextString = IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
			JsonObject mappingJsonObject = JsonParser.parseString(mappingContextString).getAsJsonObject();
			return mappingJsonObject.get("@context").getAsJsonObject();
		} catch (IOException e) {
			throw new RuntimeException("Mapping file not found");
		}
	}

	private void createMapInverseProperties(OntModel ontologyModel) {
		ExtendedIterator<OntProperty> listAllOntProperties = ontologyModel.listAllOntProperties();
		while (listAllOntProperties.hasNext()) {
			OntProperty prop = listAllOntProperties.next();
			if (prop.hasInverse()) {
				OntProperty inverseOf = prop.getInverseOf();
				this.mapInverseProperties.put(prop.getURI(), inverseOf);
			}
		}
	}

	private Individual createResourceModel(JsonObject mappingContext, CSVRecord record) {
		OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_DL_MEM);
		OntClass resourceClass = model.createClass(mappingContext.get("@type").getAsString());

		String uri = null;
		try {
			uri = createResourceUri(mappingContext, record, resourceClass.getURI());
		} catch (UriPropertyNullException e) {
			return null;
		}

		model.createClass(mappingContext.get("@type").getAsString());
		Individual individual = model.createIndividual(uri, resourceClass);
		if (!mappingContext.isJsonNull()) {
			Set<Entry<String, JsonElement>> entrySet = mappingContext.getAsJsonObject().entrySet();
			for (Entry<String, JsonElement> entry : entrySet) {
				String csvHeader = entry.getKey();
				if (csvHeader.equals("@type") || csvHeader.equals("@uriProperty")) {
					continue;
				}
				if (entry.getValue().isJsonPrimitive()) {
					DatatypeProperty property = model.createDatatypeProperty(entry.getValue().getAsString());
					try {
						String recordValue = record.get(csvHeader);
						recordValue = new String(recordValue.getBytes(this.csvEncode), this.csvEncode);
						individual.addProperty(property, recordValue);
					} catch (UnsupportedEncodingException e) {
						e.printStackTrace();
					}
				}
				if (entry.getValue().isJsonObject()) {
					Individual innerResource = createResourceModel(entry.getValue().getAsJsonObject(), record);
					if (innerResource == null) {
						continue;
					}
					ObjectProperty property = model.createObjectProperty(csvHeader);
					individual.addProperty(property, innerResource);

					if (mapInverseProperties.get(property.getURI()) != null) {
						OntProperty inverseOf = mapInverseProperties.get(property.getURI());
						innerResource.addProperty(inverseOf, individual);
					}
					individual.getModel().add(innerResource.getModel());
				}
				if (entry.getValue().isJsonArray()) {
					JsonArray asJsonArray = entry.getValue().getAsJsonArray();
					Iterator<JsonElement> iterator = asJsonArray.iterator();
					while (iterator.hasNext()) {
						JsonElement next = iterator.next();
						if (next.isJsonObject()) {
							Individual innerResource = createResourceModel(next.getAsJsonObject(), record);
							if (innerResource == null) {
								continue;
							}
							ObjectProperty property = model.createObjectProperty(csvHeader);

							individual.addProperty(property, innerResource);

							if (mapInverseProperties.get(property.getURI()) != null) {
								OntProperty inverseOf = mapInverseProperties.get(property.getURI());
								innerResource.addProperty(inverseOf, individual);
							}
							individual.getModel().add(innerResource.getModel());
						}
					}
				}
			}
		}
		return individual;
	}

	private OntModel createOntologyModel() {
		String ontologyString = null;
		OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_LITE_MEM_RULES_INF);
		try {
			ontologyString = new String(Files.readAllBytes(Paths.get(ontologyFile)));
		} catch (IOException e) {
			return model;
		}

		model.read(new StringReader(ontologyString), null, ontologyFormat);
		return model;
	}

	private String createResourceUri(JsonObject mappingContext, CSVRecord record, String resourceTypeUri) {
		String resourceUri = resourceTypeUri;

		if (mappingContext.get("@uriProperty").isJsonPrimitive()) {
			String propertyKey = mappingContext.get("@uriProperty").getAsString();
			if (propertyKey.equalsIgnoreCase("@GenerateUri")) {
				resourceUri = UUID.randomUUID().toString();
			} else {
				resourceUri = record.get(propertyKey);
				if (StringUtils.isEmpty(resourceUri)) {
					throw new UriPropertyNullException();
				}
			}
		} else if (mappingContext.get("@uriProperty").isJsonArray()) {
			JsonArray asJsonArray = mappingContext.get("@uriProperty").getAsJsonArray();
			Iterator<JsonElement> iterator = asJsonArray.iterator();
			while (iterator.hasNext()) {
				JsonElement next = iterator.next();
				if (next.isJsonPrimitive()) {
					String propertyKey = next.getAsString();
					String csvPropertyValue = record.get(propertyKey);
					if (StringUtils.isEmpty(csvPropertyValue)) {
						throw new UriPropertyNullException();
					}
					resourceUri = resourceUri + csvPropertyValue;
				}
			}
		}

		String sha2 = sha2(resourceUri);
		URI uri = null;
		try {
			uri = new URI(this.resourceDomain + "/" + sha2);
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}
		return uri.toString();

	}

	private String sha2(String input) {
		String sha2 = null;
		try {
			MessageDigest msdDigest = MessageDigest.getInstance("SHA-256");
			msdDigest.update(input.getBytes("UTF-8"), 0, input.length());
			sha2 =  Hex.encodeHexString(msdDigest.digest());
		} catch (UnsupportedEncodingException | NoSuchAlgorithmException e) {
			System.out.println("SHA-256 error");
		}
		return sha2;
	}

	private String readResourceDomain() {
		try (FileInputStream inputStream = FileUtils.openInputStream(new File(this.mappingFile))) {
			String mappingString = IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
			JsonObject mappingJsonObject = JsonParser.parseString(mappingString).getAsJsonObject();
			String managedUri = mappingJsonObject.get("@resourceDomain").getAsString();
			return managedUri;

		} catch (IOException e) {
			throw new RuntimeException("Mapping file not found");
		}
	}

	public void register(CsvReaderListener listener) {
		this.listeners.add(listener);
	}

	public void setRdfFormat(String rdfFormat) {
		this.rdfFormat = rdfFormat;
	}

	public void setCsvEncode(String csvEncode) {
		this.csvEncode = csvEncode;
	}

	public void setCsvSeparator(String csvSeparator) {
		this.csvSeparator = csvSeparator;
	}

	public void setMappingFile(String mappingFile) {
		this.mappingFile = mappingFile;
	}

	public void setCsvFilesFolder(String csvFilesFolder) {
		this.csvFilesFolder = csvFilesFolder;
	}

	public void setOntologyFile(String ontologyFile) {
		this.ontologyFile = ontologyFile;
	}

	public void setResourceDomain(String resourceDomain) {
		this.resourceDomain = resourceDomain;
	}

	public void setRdfEncode(String rdfEncode) {
		this.rdfEncode = rdfEncode;
	}
}
