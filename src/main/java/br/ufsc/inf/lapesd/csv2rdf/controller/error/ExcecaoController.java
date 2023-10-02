package br.ufsc.inf.lapesd.csv2rdf.controller.error;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class ExcecaoController {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handlerException(Exception ex) {
        return ResponseEntity.internalServerError().body("Erro: " + ex.getMessage());
    }
}
