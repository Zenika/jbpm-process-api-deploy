package com.zenika.jbpm.apigitdeploy.controller;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.util.FS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

@RestController
public class FileBpmnController {

    @Value("${jBPMUserName}")
    String userName;

    @Value("${jBPMHostName}")
    String hostname;

    @Value("${jBPMSpaceName}")
    String space;

    @Value("${jBPMProjectName}")
    String project;

    @Value("${sshIdentityKeyPath}")
    String sshIdentity;


    private void pushChangesToRepositoryCloned(String fileCompleteName, byte[] fileBytes, String commitMsg)
            throws IOException, GitAPIException {


        String remoteUrl = "ssh://%s@%s:8001/%s/%s".formatted(userName, hostname, space, project);
        String pathFileToChange = "src/main/resources/com/%s/%s".formatted(space, project.toLowerCase());

        SshSessionFactory sshSessionFactory = new JschConfigSessionFactory() {

            @Override
            protected void configure(OpenSshConfig.Host hc, Session session) {
                session.setConfig("StrictHostKeyChecking", "no");
            }
            
            @Override
            protected JSch createDefaultJSch(FS fs) throws JSchException {
                JSch defaultJSch = super.createDefaultJSch(fs);
                defaultJSch.addIdentity(sshIdentity);
                return defaultJSch;
            }
        };

        TransportConfigCallback configCallBack = new TransportConfigCallback() {
            @Override
            public void configure(Transport transport) {
                SshTransport sshTransport = (SshTransport) transport;
                sshTransport.setSshSessionFactory(sshSessionFactory);
            }
        };

        String repoTmpName = "repoTmpDir";

        try {
            Path tmpdir = Files.createTempDirectory(repoTmpName);

            File pathTmpFile = tmpdir.toFile();

            CloneCommand cmd = Git.cloneRepository().setURI(remoteUrl)
                    .setDirectory(pathTmpFile).setBranch("master");
            cmd.setTransportConfigCallback(configCallBack);
            Git git = cmd.call();

            Path targetDir = Paths.get("%s/%s".formatted(pathTmpFile.toString(),pathFileToChange));

            File newFile = new File("%s/%s".formatted(targetDir.toString(), fileCompleteName));

            if (!newFile.exists()) {
                newFile.createNewFile();
            }

            try (OutputStream outputStream = new FileOutputStream(newFile)) {
                outputStream.write(fileBytes);
            } catch (FileNotFoundException e) {
                throw new IOException("Could not open file %s : %s".formatted(newFile.getAbsolutePath(), e));
            } catch (IOException e) {
                throw new IOException(
                        "Could not write to file %s : %s".formatted(newFile.getAbsolutePath(), e));
            }

            git.add().addFilepattern("%s/%s".formatted(pathFileToChange,fileCompleteName)).call();

            PersonIdent person = new PersonIdent("api-upload", "api-upload@zenika.com");
            git.commit().setMessage(commitMsg).setAuthor(person).setCommitter(person).call();

            git.push().call();

            Files.walk(tmpdir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);

        } catch (IOException e) {
            throw new IOException("Could not open file %s : %s".formatted(repoTmpName, e));
        }

    }


    @PostMapping("/jbpm/definition")
    @ResponseBody
    public ResponseEntity<String> model(@RequestParam("file") MultipartFile file,
            @RequestParam("commit-msg") String msg,
            RedirectAttributes redirectAttributes) {
        try {

            pushChangesToRepositoryCloned(file.getOriginalFilename(), file.getBytes(), msg);
            return new ResponseEntity<String>("success", HttpStatus.OK);

        } catch (GitAPIException | IOException e) {
            e.printStackTrace();

            return new ResponseEntity<String>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

    }


}
