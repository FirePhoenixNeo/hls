package org.fbk.cit.hlt.parsers.hls;

import javafx.util.Pair;
import org.apache.commons.cli.*;
import org.fbk.cit.hlt.parsers.hls.download.*;
import org.fbk.cit.hlt.parsers.hls.exceptions.IllegalTagSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Main class for downloading, handling 
 * and outputting a single stream event
 */
public class StreamManager implements DownloaderListener {
    protected URI uri;
    protected String label;
    protected Downloader downloader;
    protected Logger logger = LoggerFactory.getLogger(StreamManager.class);
    protected PlaylistParser parser = new PlaylistParser();
    protected long nextJobId = 1;
    protected int dropCount = 5;
    protected MasterPlaylist playlist;
    protected MediaPlaylist media;
    
    protected HashMap<Long, Pair<JobType, Downloadable>> jobs = new HashMap<>();
    protected HashSet<Integer> downloadedSegments = new HashSet<>();

    protected enum JobType {
        MASTER, MEDIA, SEGMENT
    }

    public static final int HIGH_PRIORITY = 241;
    public static final int LOW_PRIORITY = 0;
    public static final int MAX_DROP_COUNT = 5;
    
    /**
     * Create a stream manager
     *
     * @param uri a URI to a Master Playlist
     * @throws URISyntaxException
     */
    public StreamManager(String uri) throws URISyntaxException {
        this.uri = new URI(uri);
        this.label = uri;
        this.setDownloader(new SimpleDownloader());
    }
    
    public void reset() {
        playlist = null;
        media = null;
        dropCount = MAX_DROP_COUNT;
        jobs.clear();
        downloadedSegments.clear();
    }
    
    public void start(MediaType type) {
        reset();
        logger.info("["+label+"] Starting handling stream");
        
        //Submitting a job for a Master Playlist and waiting until Master Playlist is populated
        submitJob(JobType.MASTER, null, uri);
        downloader.waitUntilEvent();
        
        //Halting on error
        if (playlist == null) {
            logger.warn("["+label+"] Master Playlist haven't been downloaded. Halting");
            return;
        }
        logger.info("["+label+"] Downloaded the Master Playlist");
        
        //Trying to select the best quality media of the selected type
        MediaPlaylist selectedMedia = null;
        for (MediaPlaylist candMedia : playlist.getElements()) {
            if (candMedia.getMediaType() != type) {
                continue;
            }
            if (selectedMedia == null || candMedia.bandwidth > selectedMedia.bandwidth) {
                selectedMedia = candMedia;
            }
        }
        if (selectedMedia == null) {
            logger.info("["+label+"] Can't find media of the selected type. Halting");
            return;
        }

        //Check if we can actually parse the media URL
        URI currentMediaUri = null;
        try {
            currentMediaUri = new URI(selectedMedia.getBaseURI());
        } catch (URISyntaxException e) {
            logger.warn("["+label+"] Can't parse media URI. Halting");
            return;
        }
        
        //Downloading the corresponding Media Playlist
        while (dropCount > 0) {
            long jobId = getNextJobId();
            MediaPlaylist currentMedia = null;
            try {
                currentMedia = selectedMedia.clone();
            } catch (CloneNotSupportedException e) {
                logger.warn("[" + label + "] Media Playlist could be cloned. Halting");
                return;
            }
            jobs.put(jobId, new Pair<>(JobType.MEDIA, currentMedia));
            downloader.download(jobId, currentMediaUri, HIGH_PRIORITY);
            try {
                while (jobId != downloader.waitUntilEvent()) {
                    //Wait until we catch our Media event
                }
            } catch (Exception e) {
                //If something happened with our queue, we should halt
                e.printStackTrace();
                logger.warn("["+label+"] Halting due to Downloader failure: "+dropCount);
                return;
            }

            //Trying again on error
            if (media == null) {
                logger.warn("["+label+"] Media Playlist haven't been downloaded. Retries left: "+dropCount);
                continue;
            }
            
            ArrayList<Segment> segments = media.getSegments();
            if (segments.size() == 0) {
                logger.warn("[" + label + "] Downloaded empty Media Playlist. Retries left: " + dropCount);
                dropCount--;
            } else {
                logger.info("["+label+"] Downloaded Media Playlist. Segments from "+media.getStartingSequence()+" to "+media.getCurrentSequence());
            }
            
            //Adding all the segments to job queue
            for (Segment segment : segments) {
                URI currentUri = null;
                try {
                    currentUri = new URI(segment.getUri());
                    if (!currentUri.isAbsolute()) {
                        currentUri = currentMediaUri.resolve(currentUri);
                    }
                } catch (URISyntaxException e) {
                    logger.warn("["+label+"] Can't parse URI for segment #"+segment.getSequence());
                    continue;
                }
                submitJob(JobType.SEGMENT, segment, currentUri);
            }

            //Clearing media, because now it is obsolete
            long sleepDuration = Math.round(media.getTargetDuration()*500);
            media = null;
            try {
                //Sleeping for half of the target duration
                Thread.sleep(sleepDuration);
            } catch (InterruptedException e) {
                //Sleep interruption is fine for us
            }
        }

        logger.info("["+label+"] Downloading halted. Downloaded "+downloadedSegments.size()+" segments");
    }

    private void submitJob(JobType type, Downloadable dwn, URI uri) {
        submitJob(type, dwn, uri, LOW_PRIORITY);
    }
    
    private void submitJob(JobType type, Downloadable dwn, URI uri, int priority) {
        long jobId = getNextJobId();
        jobs.put(jobId, new Pair<>(type, dwn));
        downloader.download(jobId, uri, priority);
    }

    private long getNextJobId() {
        return nextJobId++;
    }

    @Override
    public void onFinish(long jobId, byte[] result) {
        logger.debug("[" + label + "] job #"+jobId+" OK");
        Pair<JobType, Downloadable> job = jobs.get(jobId);
        switch (job.getKey()) {
            case MASTER:
                try {
                    playlist = parser.parseTwitchMaster(new String(result));
                } catch (IllegalTagSequence illegalTagSequence) {
                    logger.warn("[" + label + "] Parsing failed for Master Playlist");
                    dropCount--;
                    return;
                }
                break;
            case MEDIA:
                try {
                    media = (MediaPlaylist) parser.parse((Playlist) job.getValue(), new String(result));
                } catch (IllegalTagSequence illegalTagSequence) {
                    logger.warn("[" + label + "] Parsing failed for Media Playlist");
                    dropCount--;
                    return;
                }
                break;
            case SEGMENT:
                Segment segment = (Segment) job.getValue();
                downloadedSegments.add(segment.getSequence());
                logger.info("[" + label + "] downloaded segment #" + segment.getSequence());
                break;
        }
        
        //Restore the amount of allowed failures
        if (dropCount < MAX_DROP_COUNT) {
            dropCount++;
        }
    }

    @Override
    public void onError(long jobId, Exception e) {
        Pair<JobType, Downloadable> job = jobs.get(jobId);
        logger.warn("[" + label + "] Download job of type \""+job.getKey().name()+"\" has failed: "+e.getMessage());
        dropCount--;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public URI getUri() {
        return uri;
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    public Downloader getDownloader() {
        return downloader;
    }

    public void setDownloader(Downloader downloader) {
        if (this.downloader != null) {
            this.downloader.unsubscribe(this);
        }
        this.downloader = downloader;
        this.downloader.subscribe(this);
    }
    
    public static void main(String args[]) {
        //Options specification
        Options options = new Options();
        options.addOption("u", "url", true, "URL of the Master Playlist to download");
        options.addOption("l", "label", true, "Convenient label of the stream");
        
        CommandLineParser parser = new PosixParser();
        CommandLine line;
        StreamManager manager;
        try {
            line = parser.parse(options, args);
            if (!line.hasOption("url")) {
                throw new ParseException("Missing parameter url");
            }

            try {
                manager = new StreamManager(line.getOptionValue("url"));
            } catch (URISyntaxException e) {
                throw new ParseException("Malformed url");
            }
        } catch (ParseException e) {
            String footer = "\nError: "+e.getMessage();
            new HelpFormatter().printHelp(400, "<whatever>", "\n", options, footer, true);
            return;
        }

        if (line.hasOption("label")) {
            manager.setLabel(line.getOptionValue("label"));
        }
        manager.start(MediaType.VIDEO);
    }
}
