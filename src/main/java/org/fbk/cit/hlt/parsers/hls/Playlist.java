package org.fbk.cit.hlt.parsers.hls;

import org.fbk.cit.hlt.parsers.hls.download.Downloadable;
import org.fbk.cit.hlt.parsers.hls.tags.general.OpeningTag;
import org.fbk.cit.hlt.parsers.hls.exceptions.*;
import org.fbk.cit.hlt.parsers.hls.tags.Tag;

import java.util.ArrayList;

/**
 * Generic playlist entity
 */
public abstract class Playlist implements Downloadable {
    protected int version;
    protected ArrayList<Tag> tags;
    protected boolean awaitingURI;

    /**
     * Create an empty playlist,
     * you probably won't need to directly access this constructor
     * use {@link PlaylistParser} methods instead
     */
    public Playlist() {
        version = 1;
        tags = new ArrayList<>();
        awaitingURI = false;
    }

    public boolean isValid() {
        return tags.size() != 0;
    }

    public void applyTag(Tag tag) throws IllegalTagSequence {
        if (!isValid() && !(tag instanceof OpeningTag)) {
            throw new IllegalTagSequence("Missing #EXTM3U");
        }

        if (awaitingURI) {
            throw new IllegalTagSequence("Didn't expect a tag, awaiting URI");
        }

        if (tag.shouldBeUnique()) {
            for (Tag checkTag : tags) {
                if (checkTag.getClass().equals(tag.getClass())) {
                    throw new IllegalTagSequence(tag.getName()+" should be unique");
                }
            }
        }

        if (tag.minVersion() > version) {
            version = tag.minVersion();
        }

        tags.add(tag);
        if (tag.shouldBeFollowedByURI()) {
            awaitingURI = true;
        }
    }

    public abstract void addURL(String uri) throws RequiredTagMissing;
}
