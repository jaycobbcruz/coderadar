package org.wickedsource.coderadar.job.associate;

import static com.codahale.metrics.MetricRegistry.name;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.wickedsource.coderadar.analyzer.api.ChangeType;
import org.wickedsource.coderadar.commit.domain.Commit;
import org.wickedsource.coderadar.commit.domain.CommitRepository;
import org.wickedsource.coderadar.commit.domain.CommitToFileAssociation;
import org.wickedsource.coderadar.commit.domain.CommitToFileAssociationRepository;
import org.wickedsource.coderadar.commit.event.CommitToFileAssociatedEvent;
import org.wickedsource.coderadar.file.domain.File;
import org.wickedsource.coderadar.file.domain.FileIdentity;
import org.wickedsource.coderadar.file.domain.FileRepository;
import org.wickedsource.coderadar.file.domain.GitLogEntry;
import org.wickedsource.coderadar.file.domain.GitLogEntryRepository;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;

@Service
public class CommitToFileAssociator {

  private Logger logger = LoggerFactory.getLogger(CommitToFileAssociator.class);

  private GitLogEntryRepository gitLogEntryRepository;

  private FileRepository fileRepository;

  private CommitRepository commitRepository;

  private CommitToFileAssociationRepository commitToFileAssociationRepository;

  private ApplicationEventPublisher eventPublisher;

  private Meter commitsMeter;

  private Meter filesMeter;

  private static final List<ChangeType> ALL_BUT_DELETED =
      Arrays.asList(
          ChangeType.ADD,
          ChangeType.RENAME,
          ChangeType.COPY,
          ChangeType.MODIFY,
          ChangeType.UNCHANGED);

  @Autowired
  public CommitToFileAssociator(
      GitLogEntryRepository gitLogEntryRepository,
      FileRepository fileRepository,
      CommitRepository commitRepository,
      CommitToFileAssociationRepository commitToFileAssociationRepository,
      ApplicationEventPublisher eventPublisher,
      MetricRegistry metricRegistry) {
    this.gitLogEntryRepository = gitLogEntryRepository;
    this.fileRepository = fileRepository;
    this.commitRepository = commitRepository;
    this.commitToFileAssociationRepository = commitToFileAssociationRepository;
    this.eventPublisher = eventPublisher;
    this.commitsMeter = metricRegistry.meter(name(CommitToFileAssociator.class, "commits"));
    this.filesMeter = metricRegistry.meter(name(CommitToFileAssociator.class, "files"));
  }

  /**
   * Goes through the git log of all files of the given commit and creates {@link File} entities for
   * each. If a {@link File} for the file is already present, the file in the commit is associated
   * with the present to file so that RENAMED files can be traced to their new filename in the
   * database. Each {@link File} entity will then be associated with the {@link Commit} entity in
   * order to build a searchable database.
   *
   * @param commit the commit whose files to associate
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void associateFilesOfCommit(Commit commit) {
    checkEligibility(commit);

    List<File> touchedFiles = associateWithTouchedFiles(commit);
    filesMeter.mark(touchedFiles.size());

    int unchangedFilesCount = associateWithUnchangedFiles(commit, touchedFiles);
    filesMeter.mark(unchangedFilesCount);

    logger.info(
        "associated {} CHANGED files and {} UNCHANGED files with commit {} (id: {})",
        touchedFiles.size(),
        unchangedFilesCount,
        commit.getName(),
        commit.getId());

    commit.setMerged(true);
    commitRepository.save(commit);
    // commit entity is updated in database implicitly
    commitsMeter.mark();
  }

  private List<File> associateWithTouchedFiles(Commit commit) {

    List<File> associatedFiles = new ArrayList<>();

    for (GitLogEntry entry : gitLogEntryRepository.findByCommitId(commit.getId())) {

      File file = null;

      if (entry.getChangeType() == ChangeType.RENAME) {
        File existingFile = loadExistingFile(commit, entry.getOldFilepath());
        if (existingFile != null) {
          file = createNewFileWithSameIdentity(existingFile, entry.getFilepath());
        }
      } else {
        file = loadExistingFile(commit, entry.getFilepath());
      }

      if (file == null || entry.getChangeType() == ChangeType.RENAME) {
        file = createNewFile(entry.getFilepath());
      }

      associateFile(commit, file, entry.getChangeType());
      associatedFiles.add(file);
    }

    return associatedFiles;
  }

  private void associateFile(Commit commit, File file, ChangeType changeType) {
    CommitToFileAssociation association = new CommitToFileAssociation(commit, file, changeType);
    commit.getFiles().add(association);
    saveCommitToFileAssociation(association);
  }

  private File loadExistingFile(Commit commit, String filepath) {
    return fileRepository.findInCommit(
        filepath, commit.getFirstParent(), commit.getProject().getId());
  }

  private File createNewFile(String filepath) {
    File newFile = new File();
    newFile.setIdentity(new FileIdentity());
    newFile.setFilepath(filepath);
    return fileRepository.save(newFile);
  }

  private File createNewFileWithSameIdentity(File file, String filepath) {
    File newFile = new File();
    newFile.setIdentity(file.getIdentity());
    newFile.setFilepath(filepath);
    return fileRepository.save(newFile);
  }

  private int associateWithUnchangedFiles(Commit commit, List<File> touchedFiles) {
    List<File> unchangedFiles =
        fileRepository.findInCommit(
            ALL_BUT_DELETED, commit.getFirstParent(), commit.getProject().getId());
    unchangedFiles.removeAll(touchedFiles);
    for (File file : unchangedFiles) {
      associateFile(commit, file, ChangeType.UNCHANGED);
    }
    return unchangedFiles.size();
  }

  private void saveCommitToFileAssociation(CommitToFileAssociation association) {
    eventPublisher.publishEvent(new CommitToFileAssociatedEvent(association));
    commitToFileAssociationRepository.save(association);
  }

  private void checkEligibility(Commit commit) {
    if (commit.isMerged()) {
      throw new IllegalStateException(String.format("commit is already merged: %s", commit));
    }
    if (!commit.isScanned()) {
      throw new IllegalStateException(String.format("commit is not yet scanned: %s", commit));
    }
  }
}
