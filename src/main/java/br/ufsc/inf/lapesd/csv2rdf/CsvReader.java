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

import javax.xml.bind.DatatypeConverter;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jena.ontology.DatatypeProperty;
import org.apache.jena.ontology.Individual;
import org.apache.jena.ontology.ObjectProperty;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.util.iterator.ExtendedIterator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CsvReader {

    private String rdfFolder;
    private String csvFilesFolder;
    private String ontologyFile;
    private String prefix = "";
    private String csvEncode = "UTF-8";
    private String csvSeparator = "COMMA";
    private String ontologyFormat = Lang.N3.getName();
    private String rdfFormat = Lang.NTRIPLES.getName();
    private Map<String, OntProperty> mapInverseProperties = new HashMap<>();

    private Model tempModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_DL_MEM);
    private int individualsAddedToTempModel = 0;
    private int resourcesPerFile = 0;

    private boolean writeToFile = true;
    private boolean singleRdfOutputFile = true;

    private List<CsvReaderListener> listeners = new ArrayList<>();

    public CsvReader() {
        JsonObject mappingConfing = createConfigMapping();
        this.rdfFolder = mappingConfing.get("rdfFolder").getAsString();
        this.csvFilesFolder = mappingConfing.get("csvFilesFolder").getAsString();
        this.ontologyFile = mappingConfing.get("ontologyFile").getAsString();
        this.prefix = mappingConfing.get("prefix").getAsString();
        this.csvEncode = mappingConfing.get("csvEncode").getAsString();
        this.csvSeparator = mappingConfing.get("csvSeparator").getAsString();
        this.ontologyFormat = mappingConfing.get("ontologyFormat").getAsString();
        this.resourcesPerFile = mappingConfing.get("resourcesPerFile").getAsInt();
        this.singleRdfOutputFile = mappingConfing.get("singleRdfOutputFile").getAsBoolean();

        OntModel ontologyModel = createOntologyModel();
        this.createMapInverseProperties(ontologyModel);
    }

    public void process() {
        try {
            String csvFilesFolder = this.csvFilesFolder;
            Collection<File> files = FileUtils.listFiles(new File(csvFilesFolder), null, true);
            for (File file : files) {
                System.out.println("reading " + file.getName());
                Reader in = new FileReader(file.getPath());

                Iterable<CSVRecord> records = null;

                if (csvSeparator.equalsIgnoreCase("COMMA")) {
                    records = CSVFormat.RFC4180.withFirstRecordAsHeader().parse(in);
                }
                if (csvSeparator.equalsIgnoreCase("TAB")) {
                    records = CSVFormat.TDF.withFirstRecordAsHeader().parse(in);
                }

                for (CSVRecord record : records) {
                    JsonObject mappingContext = createContextMapping();
                    Individual resource = createResourceModel(mappingContext, record);
                    if (this.singleRdfOutputFile) {
                        if (this.individualsAddedToTempModel == 1000) {
                            writeToFile(this.tempModel);
                            this.tempModel.removeAll();
                            this.individualsAddedToTempModel = 0;
                        }
                    }

                    else if (this.resourcesPerFile == individualsAddedToTempModel) {
                        writeToFile(this.tempModel);
                        this.tempModel.removeAll();
                        this.individualsAddedToTempModel = 0;
                    }

                    this.tempModel.add(resource.getModel());
                    this.individualsAddedToTempModel++;
                    for (CsvReaderListener listener : this.listeners) {
                        listener.justRead(resource.getModel());
                    }
                }
            }

            writeToFile(this.tempModel);

            for (CsvReaderListener listener : this.listeners) {
                listener.readProcessFinished();
            }
            System.out.println("Process finished");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private JsonObject createContextMapping() {
        try (FileInputStream inputStream = FileUtils.openInputStream(new File("mapping.jsonld"))) {
            String mappingContextString = IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
            JsonObject mappingJsonObject = new JsonParser().parse(mappingContextString).getAsJsonObject();
            return mappingJsonObject.get("@context").getAsJsonObject();
        } catch (IOException e) {
            throw new RuntimeException("Mapping file not found");
        }
    }

    private JsonObject createConfigMapping() {
        try (FileInputStream inputStream = FileUtils.openInputStream(new File("mapping.jsonld"))) {
            String mappingContextString = IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
            JsonObject mappingJsonObject = new JsonParser().parse(mappingContextString).getAsJsonObject();
            return mappingJsonObject.get("@configuration").getAsJsonObject();
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
        String uri = createResourceUri(mappingContext, record, resourceClass.getURI());
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
        return individual;
    }

    private void writeToFile(Model model) {
        if (!this.writeToFile) {
            return;
        }

        File directory = new File(this.rdfFolder);
        if (!directory.exists()) {
            directory.mkdir();
        }

        String fileName = this.rdfFolder + "/output_" + UUID.randomUUID().toString() + ".ntriples";
        write(model, fileName);
    }

    private void write(Model model, String fileName) {
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
        try {
            ontologyString = new String(Files.readAllBytes(Paths.get(this.ontologyFile)));
        } catch (IOException e) {
            e.printStackTrace();
        }

        OntModel ontologyModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_LITE_MEM_RULES_INF);
        ontologyModel.read(new StringReader(ontologyString), null, ontologyFormat);
        return ontologyModel;
    }

    private String createResourceUri(JsonObject mappingContext, CSVRecord record, String resourceTypeUri) {
        String resourceUri = resourceTypeUri;

        if (mappingContext.get("@uriProperty").isJsonPrimitive()) {
            String propertyKey = mappingContext.get("@uriProperty").getAsString();
            if (propertyKey.equalsIgnoreCase("RandomUri")) {
                resourceUri = UUID.randomUUID().toString();
            } else {
                resourceUri = record.get(propertyKey);
            }
        } else if (mappingContext.get("@uriProperty").isJsonArray()) {
            JsonArray asJsonArray = mappingContext.get("@uriProperty").getAsJsonArray();
            Iterator<JsonElement> iterator = asJsonArray.iterator();
            while (iterator.hasNext()) {
                JsonElement next = iterator.next();
                if (next.isJsonPrimitive()) {
                    String propertyKey = next.getAsString();
                    resourceUri = resourceUri + record.get(propertyKey);
                }
            }
        }

        String sha1 = sha1(resourceUri);
        return this.prefix + sha1;
    }

    private String sha1(String input) {
        String sha1 = null;
        try {
            MessageDigest msdDigest = MessageDigest.getInstance("SHA-1");
            msdDigest.update(input.getBytes("UTF-8"), 0, input.length());
            sha1 = DatatypeConverter.printHexBinary(msdDigest.digest());
        } catch (UnsupportedEncodingException | NoSuchAlgorithmException e) {
            System.out.println("SHA1 error");
        }
        return sha1;
    }

    public void register(CsvReaderListener listener) {
        this.listeners.add(listener);
    }

    public String getPrefix() {
        return prefix;
    }

    public void setWriteToFile(boolean writeToFile) {
        this.writeToFile = writeToFile;
    }

    public void setOntologyFormat(String ontologyFormat) {
        this.ontologyFormat = ontologyFormat;
    }

    public void setRdfFormat(String rdfFormat) {
        this.rdfFormat = rdfFormat;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public void setCsvEncode(String csvEncode) {
        this.csvEncode = csvEncode;
    }

    public void setCsvSeparator(String csvSeparator) {
        this.csvSeparator = csvSeparator;
    }

    public void setOntologyFile(String ontologyFile) {
        this.ontologyFile = ontologyFile;
    }

}
