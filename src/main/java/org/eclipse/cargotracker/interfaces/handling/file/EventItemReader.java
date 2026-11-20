package org.eclipse.cargotracker.interfaces.handling.file;

import jakarta.batch.api.chunk.AbstractItemReader;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

@Dependent
@Named("EventItemReader")
public class EventItemReader extends AbstractItemReader {

  private static final String UPLOAD_DIRECTORY = "upload_directory";

  @Inject private Logger logger;
  @Inject private EventLineParser eventLineParser;

  @Inject private JobContext jobContext;
  private EventFilesCheckpoint checkpoint;
  private RandomAccessFile currentFile;

  @Override
  public void open(Serializable checkpoint) throws Exception {
    File uploadDirectory = new File(jobContext.getProperties().getProperty(UPLOAD_DIRECTORY));

    if (checkpoint == null) {
      this.checkpoint = new EventFilesCheckpoint();
      logger.log(Level.INFO, "Scanning upload directory: {0}", uploadDirectory);

      if (!uploadDirectory.exists()) {
        logger.log(Level.INFO, "Upload directory does not exist, creating it");
        uploadDirectory.mkdirs();
      } else {
        this.checkpoint.setFiles(Arrays.asList(uploadDirectory.listFiles()));
      }
    } else {
      logger.log(Level.INFO, "Starting from previous checkpoint");
      this.checkpoint = (EventFilesCheckpoint) checkpoint;
    }

    File file = this.checkpoint.currentFile();

    if (file == null) {
      logger.log(Level.INFO, "No files to process");
      currentFile = null;
    } else {
      currentFile = new RandomAccessFile(file, "r");
      logger.log(Level.INFO, "Processing file: {0}", file);
      currentFile.seek(this.checkpoint.getFilePointer());
    }
  }

  @Override
  public Object readItem() throws Exception {
    if (currentFile != null) {
      String line = currentFile.readLine();

      if (line != null) {
        this.checkpoint.setFilePointer(currentFile.getFilePointer());
        return eventLineParser.parse(line);
      } else {
        logger.log(
            Level.INFO, "Finished processing file, deleting: {0}", this.checkpoint.currentFile());
        closeCurrentFile();
        this.checkpoint.currentFile().delete();
        File nextFile = this.checkpoint.nextFile();

        if (nextFile == null) {
          logger.log(Level.INFO, "No more files to process");
          return null;
        } else {
          currentFile = new RandomAccessFile(nextFile, "r");
          logger.log(Level.INFO, "Processing file: {0}", nextFile);
          return readItem();
        }
      }
    } else {
      return null;
    }
  }

  private void closeCurrentFile() throws IOException {
    if (currentFile != null) {
      currentFile.close();
      currentFile = null;
    }
  }

  @Override
  public Serializable checkpointInfo() throws Exception {
    return this.checkpoint;
  }
}
