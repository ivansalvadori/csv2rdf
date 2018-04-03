package br.ufsc.inf.lapesd.csv2rdf;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
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

import javax.annotation.PostConstruct;
import javax.xml.bind.DatatypeConverter;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@Component
public class CsvReader {

    private String resourceDomain = "http://example.com";

    @Value("${config.rdfFolder}")
    private String rdfFolder;

    @Value("${config.csvFilesFolder}")
    private String csvFilesFolder;

    @Value("${config.csvEncode}")
    private String csvEncode = "UTF-8";

    @Value("${config.csvSeparator}")
    private String csvSeparator = "COMMA";

    @Value("${config.rdfFormat}")
    private String rdfFormat = Lang.NTRIPLES.getName();

    @Value("${config.singleRdfOutputFile}")
    private boolean singleRdfOutputFile = true;

    // ignored if singleRdfOutputFile true
    @Value("${config.resourcesPerFile}")
    private int resourcesPerFile = 0;

    @Value("${config.writeToFile}")
    private boolean writeToFile = true;

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

    @Value("${config.processWhenStarted}")
    private boolean processWhenStarted = false;

    @PostConstruct
    public void init() {
        if (processWhenStarted) {
            process();
        }
    }

    public void process() {
        this.tempModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_DL_MEM);
        this.resourceDomain = this.readResourceDomain();

        OntModel ontologyModel = createOntologyModel();
        this.createMapInverseProperties(ontologyModel);

        try {
            String csvFilesFolder = this.csvFilesFolder;
            Collection<File> files = FileUtils.listFiles(new File(csvFilesFolder), null, true);
            for (File file : files) {
                System.out.println("reading " + file.getName());
                Reader in = new FileReader(file.getPath());

                Iterable<CSVRecord> records = null;

                if (csvSeparator.equalsIgnoreCase("COMMA")) {
                    records = CSVFormat.DEFAULT.withFirstRecordAsHeader().withAllowMissingColumnNames().parse(in);
                }
                if (csvSeparator.equalsIgnoreCase("TAB")) {
                    records = CSVFormat.TDF.withFirstRecordAsHeader().parse(in);
                }

                for (CSVRecord record : records) {
                    JsonObject mappingContext = createContextMapping();
                    Individual resource;
                    resource = createResourceModel(mappingContext, record);
                    if (resource == null) {
                        continue;
                    }

                    if (this.singleRdfOutputFile) {
                        if (this.individualsAddedToTempModel == this.inMemoryModelSize) {
                            writeToFile(this.tempModel, currentFileId);
                            this.tempModel.removeAll();
                            this.individualsAddedToTempModel = 0;
                        }
                    }

                    else if (this.resourcesPerFile == individualsAddedToTempModel) {
                        this.currentFileId = UUID.randomUUID().toString();
                        writeToFile(this.tempModel, currentFileId);
                        this.tempModel.removeAll();
                        this.individualsAddedToTempModel = 0;
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

            writeToFile(this.tempModel, currentFileId);

            for (CsvReaderListener listener : this.listeners) {
                listener.readProcessFinished();
            }
            System.out.println("Process finished. Record processed: " + totalProcessedRecords);

        } catch (Exception e) {
            e.printStackTrace();
        }
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
            JsonObject mappingJsonObject = new JsonParser().parse(mappingContextString).getAsJsonObject();
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
                if (entry.getKey().equals("@type") || entry.getKey().equals("@uriProperty")) {
                    continue;
                }
                if (entry.getValue().isJsonPrimitive()) {
                    DatatypeProperty property = model.createDatatypeProperty(entry.getValue().getAsString());
                    try {
                        String recordValue = record.get(entry.getKey());
                        recordValue = new String(recordValue.getBytes(this.csvEncode), "UTF-8");
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
                    ObjectProperty property = model.createObjectProperty(entry.getKey());

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
                            ObjectProperty property = model.createObjectProperty(entry.getKey());

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

    private void writeToFile(Model model, String fileId) {
        String fileName = this.rdfFolder + "/output_" + fileId + ".ntriples";
        write(model, fileName);
    }

    private void write(Model model, String fileName) {
        if (!this.writeToFile) {
            return;
        }

        File directory = new File(this.rdfFolder);
        if (!directory.exists()) {
            directory.mkdir();
        }

        try (FileWriter fostream = new FileWriter(fileName, true);) {
            BufferedWriter out = new BufferedWriter(fostream);
            model.write(out, this.rdfFormat);
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
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
            if (propertyKey.equalsIgnoreCase("RandomUri")) {
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
            sha2 = DatatypeConverter.printHexBinary(msdDigest.digest());
        } catch (UnsupportedEncodingException | NoSuchAlgorithmException e) {
            System.out.println("SHA-256 error");
        }
        return sha2;
    }

    private String readResourceDomain() {
        try (FileInputStream inputStream = FileUtils.openInputStream(new File(this.mappingFile))) {
            String mappingString = IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
            JsonObject mappingJsonObject = new JsonParser().parse(mappingString).getAsJsonObject();
            String managedUri = mappingJsonObject.get("@resourceDomain").getAsString();
            return managedUri;

        } catch (IOException e) {
            throw new RuntimeException("Mapping file not found");
        }
    }

    public void register(CsvReaderListener listener) {
        this.listeners.add(listener);
    }

    public void setWriteToFile(boolean writeToFile) {
        this.writeToFile = writeToFile;
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

    public void setRdfFolder(String rdfFolder) {
        this.rdfFolder = rdfFolder;
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

}
