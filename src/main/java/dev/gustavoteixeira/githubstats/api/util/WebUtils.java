package dev.gustavoteixeira.githubstats.api.util;

import dev.gustavoteixeira.githubstats.api.dto.UnprocessedElementDTO;
import dev.gustavoteixeira.githubstats.api.exception.GitHubRepositoryNotFound;
import dev.gustavoteixeira.githubstats.api.exception.InvalidGitHubRepositoryURL;
import dev.gustavoteixeira.githubstats.api.exception.ProcessingError;
import dev.gustavoteixeira.githubstats.api.service.ProcessorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static dev.gustavoteixeira.githubstats.api.util.TimeUtils.getTimeDifference;
import static org.apache.commons.lang3.StringUtils.substringBetween;

@Service
public class WebUtils {
    private static Logger logger = LoggerFactory.getLogger(WebUtils.class);

    @Autowired
    private ProcessorService processorService;

    public void mapRepository(String repositoryURL) {
        logger.info("WebUtils.mapRepository - Start - Processing");
        long start = System.currentTimeMillis();

        List<String> elementListOfTheFirstPage = getGithubRepositoryElementsList(repositoryURL);
        if (elementListOfTheFirstPage.size() != 0) {
            List<UnprocessedElementDTO> remainingDirectories = transformRawElementsToUnprocessedElements(elementListOfTheFirstPage);

            if (!CollectionUtils.isEmpty(remainingDirectories)) {
                recursive(remainingDirectories);
            }

            // Put the thread to sleep to guarantee that all the previous threads will be done
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                logger.error("ApiService.getRepositoryStatistics - Error - Error while trying to get the thread to sleep: {}", e.getMessage());
                throw new ProcessingError();
            }

        }

        long end = System.currentTimeMillis();
        logger.info("WebUtils.mapRepository - End - Processing time: {}", getTimeDifference(start, end));
    }

    public void recursive(List<UnprocessedElementDTO> remainingDirectory) {
        remainingDirectory.forEach(unprocessedElementDTO -> {
            List<String> elementListOfTheFirstPage = getGithubRepositoryElementsList(unprocessedElementDTO.getAddress());
            List<UnprocessedElementDTO> newlyRemainingDirectory = transformRawElementsToUnprocessedElements(elementListOfTheFirstPage);
            if (!CollectionUtils.isEmpty(newlyRemainingDirectory)) {
                recursive(newlyRemainingDirectory);
            }
        });
    }

    public static List<UnprocessedElementDTO> getUnprocessedElementsDTO(List<String> elementList) {
        List<UnprocessedElementDTO> unprocessedElementsList = new ArrayList<>();

        elementList.forEach(element -> {
            if (element != null && !element.isBlank()) {
                String elementType = element.contains("blob") ? "File" : "Directory";
                String elementAddress = "https://www.github.com" + substringBetween(element, "href=\"", "\">");
                UnprocessedElementDTO e = new UnprocessedElementDTO();
                e.setType(elementType);
                e.setAddress(elementAddress);
                unprocessedElementsList.add(e);
            }
        });

        return unprocessedElementsList;
    }

    public List<UnprocessedElementDTO> transformRawElementsToUnprocessedElements(List<String> elementListOfTheFirstPage) {
        List<UnprocessedElementDTO> filesAndDirectories = getUnprocessedElementsDTO(elementListOfTheFirstPage);

        filesAndDirectories.forEach(element -> {
            if ((element.getType()).equals("File")) {
                logger.info("WebUtils.transformRawElementsToUnprocessedElements - Processing - Starting new thread with url: {}", element.getAddress());
                new Thread(() -> processorService.persistElementInfo(element.getAddress())).start();
            }
        });

        filesAndDirectories.removeIf(e -> e.getType().equals("File"));
        return filesAndDirectories;
    }

    public static List<String> getGithubRepositoryElementsList(String fullURL) {
        List<String> elements = new ArrayList<>();

        try (var br = new BufferedReader(new InputStreamReader(getUrl(fullURL).openStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("data-pjax=\"#repo-content-pjax-container\"")) {
                    elements.add(line);
                }
            }
        } catch (IOException e) {
            logger.error("WebUtils.getGithubRepositoryElementsList - Error - URL: {}", fullURL);
            throw new GitHubRepositoryNotFound();
        }

        return elements;
    }

    private static URL getUrl(String fullURL) {
        URL url = null;
        try {
            url = new URL(fullURL);
        } catch (MalformedURLException e) {
            logger.error("WebUtils.getUrl - Error - MalformedURLException - URL: {}", fullURL);
            throw new InvalidGitHubRepositoryURL();
        }

        return url;
    }

}