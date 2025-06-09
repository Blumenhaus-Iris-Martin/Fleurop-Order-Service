package de.bim.fleurop;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import org.apache.avro.Schema;
import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class SchemaRegistryUploader {
    private static final Logger logger = LoggerFactory.getLogger(SchemaRegistryUploader.class);

    private final SchemaRegistryClient schemaRegistryClient;
    private final String schemaDirectory;

    private final Map<String, String> topicSubjectMapping;

    public SchemaRegistryUploader(String schemaRegistryUrl) {
        Map<String, String> config = new HashMap<>();

        this.schemaRegistryClient = new CachedSchemaRegistryClient(schemaRegistryUrl, 100, config);
        this.schemaDirectory = System.getProperty("user.dir") + "/src/main/avro";

        this.topicSubjectMapping = new HashMap<>();
        this.topicSubjectMapping.put("order-events", "order-events-value");
    }

    public void uploadAllSchemas() {
        logger.info("Starting schema upload...");

        try {
            Path schemaPath = Paths.get(schemaDirectory);
            if (!Files.exists(schemaPath)) {
                logger.error("Schema directory does not exist: {}", schemaDirectory);
                return;
            }

            List<Path> schemaFiles = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(schemaPath)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".avsc"))
                        .forEach(schemaFiles::add);
            }

            List<Path> individualSchemas = new ArrayList<>();
            List<Path> unionSchemas = new ArrayList<>();

            for (Path schemaFile : schemaFiles) {
                if (isUnionSchema(schemaFile)) {
                    unionSchemas.add(schemaFile);
                } else {
                    individualSchemas.add(schemaFile);
                }
            }
            logger.info("Uploading {} individual schemas", individualSchemas.size());
            individualSchemas.forEach(this::uploadSchema);

            logger.info("Uploading {} union schemas", unionSchemas.size());
            unionSchemas.forEach(this::uploadUnionSchema);

            logger.info("Schema upload finished");

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void uploadSchema(Path schemaFile) {
        logger.info("Processing schema file: {}", schemaFile.getFileName());

        try {
            String schemaContent = Files.readString(schemaFile);
            Schema schema = new Schema.Parser().parse(schemaContent);

            String subject = determineSubject(schemaFile, schema);

            try {
                String existingSchema = schemaRegistryClient.getLatestSchemaMetadata(subject).getSchema();
                logger.info("Schema {}", existingSchema);
            } catch (RestClientException e) {
                if (e.getErrorCode() == 40401) {
                    logger.info("Schema {} does not exist yet", subject);
                } else {
                    throw e;
                }
            }

            int schemaId = schemaRegistryClient.register(subject, new AvroSchema(schema));
            logger.info("Schema {} registered with ID {}", subject, schemaId);


        } catch (IOException | RestClientException e) {
            logger.error("Failed to upload schema from file: {}", schemaFile, e);
            throw new RuntimeException(e);
        }
    }

    private String determineSubject(Path schemaFile, Schema schema) {
        String fileName = schemaFile.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));

        if (schema.getNamespace() != null && schema.getName() != null) {
            return schema.getNamespace() + "." + schema.getName();
        } else if( schema.getName() != null ) {
            return schema.getName();
        } else {
            return baseName;
        }
    }


    private void uploadUnionSchema(Path path) {
        throw new NotImplementedException("determineSubject");
    }

    private boolean isUnionSchema(Path schemaFile) {
        try {
            String content = Files.readString(schemaFile).trim();
            return content.startsWith("[") && content.endsWith("]");
        } catch (IOException e) {
            logger.error("Error reading schema file: {}", schemaFile, e);
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        String schemaRegistryUrl = System.getProperty("schema.registry.url", "http://192.168.2.243:8081");

        try {
            SchemaRegistryUploader schemaRegistryUploader = new SchemaRegistryUploader(schemaRegistryUrl);
            schemaRegistryUploader.uploadAllSchemas();
        } catch (Exception e) {
            logger.error("Error uploading schemas", e);
            System.exit(1);
        } finally {
            System.exit(0);
        }
    }
}

