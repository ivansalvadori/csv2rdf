package br.ufsc.inf.lapesd.csv2rdf;

import org.apache.jena.rdf.model.Model;

public interface CsvReaderListener {

    void justRead(Model model);

    void readProcessFinished();

}
