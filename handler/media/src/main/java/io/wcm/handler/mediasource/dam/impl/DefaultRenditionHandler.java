/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2014 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.wcm.handler.mediasource.dam.impl;

import io.wcm.handler.media.MediaArgsType;
import io.wcm.handler.media.format.MediaFormat;
import io.wcm.handler.media.format.MediaFormatHandler;
import io.wcm.sling.commons.adapter.AdaptTo;
import io.wcm.wcm.commons.contenttype.FileExtension;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.adapter.Adaptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.DamConstants;
import com.day.cq.dam.api.Rendition;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

/**
 * Handles resolving DAM renditions and resizing for media handler.
 */
class DefaultRenditionHandler implements RenditionHandler {

  private final Adaptable adaptable;
  private final Set<RenditionMetadata> renditions;
  private final RenditionMetadata originalRendition;

  private static final Logger mLog = LoggerFactory.getLogger(DefaultRenditionHandler.class);

  /**
   * @param asset DAM asset
   */
  public DefaultRenditionHandler(Asset asset, Adaptable adaptable) {

    // gather rendition infos of all renditions and sort them by size (smallest first)
    this.renditions = new TreeSet<RenditionMetadata>();
    RenditionMetadata orgRendition = null;
    for (Rendition rendition : asset.getRenditions()) {
      // ignore CQ thumbnail renditions
      if (StringUtils.startsWith(rendition.getName(), DamConstants.PREFIX_ASSET_THUMBNAIL + ".")) {
        continue;
      }
      RenditionMetadata renditionMetadata = new RenditionMetadata(rendition);
      this.renditions.add(renditionMetadata);
      if (StringUtils.equals(rendition.getName(), DamConstants.ORIGINAL_FILE)) {
        orgRendition = renditionMetadata;
      }
    }
    this.originalRendition = orgRendition;

    this.adaptable = adaptable;
  }

  /**
   * @return All renditions that are available for this asset
   */
  protected Set<RenditionMetadata> getAvailableRenditions() {
    return this.renditions;
  }

  /**
   * Get all renditions that match the requested list of file extension.
   * @param fileExtensions List of file extensions
   * @return Matching renditions
   */
  private Set<RenditionMetadata> getRendtionsMatchingFileExtensions(String[] fileExtensions) {

    // if no file extension restriction get all renditions
    Set<RenditionMetadata> allRenditions = getAvailableRenditions();
    if (fileExtensions == null || fileExtensions.length == 0) {
      return allRenditions;
    }

    // otherwise return those with matching extensions
    Set<RenditionMetadata> matchingRenditions = new TreeSet<RenditionMetadata>();
    for (RenditionMetadata rendition : allRenditions) {
      for (String fileExtension : fileExtensions) {
        if (StringUtils.equalsIgnoreCase(fileExtension, rendition.getFileExtension())) {
          matchingRenditions.add(rendition);
          break;
        }
      }
    }
    return matchingRenditions;
  }

  /**
   * Get rendition (probably virtual) for given media arguments.
   * @param mediaArgs Media arguments
   * @return Rendition or null if none is matching
   */
  @Override
  public RenditionMetadata getRendition(MediaArgsType mediaArgs) {

    // get list of file extensions requested
    String[] requestedFileExtensions = getRequestedFileExtensions(mediaArgs);

    // if the array is null file extensions constraints are applied, but do not match to each other
    // - no rendition can fulfill these constraints
    if (requestedFileExtensions == null) {
      return null;
    }

    // check if a specific media size is requested
    boolean isSizeMatchingRequest = isSizeMatchingRequest(mediaArgs, requestedFileExtensions);

    // get rendition candidates matching for file extensions
    Set<RenditionMetadata> candidates = getRendtionsMatchingFileExtensions(requestedFileExtensions);

    // if request dos not contain any size restrictions return original image or first by filename matching rendition
    if (!isSizeMatchingRequest) {
      return getOriginalOrFirstRendition(candidates);
    }

    // original rendition is a image - check for matching rendition or build virtual one
    RenditionMetadata exactMatchRendition = getExactMatchRendition(candidates, mediaArgs);
    if (exactMatchRendition != null) {
      return exactMatchRendition;
    }

    // get rendition virtual rendition downscaled from existing one
    RenditionMetadata virtualRendition = getVirtualRendition(candidates, mediaArgs);
    if (virtualRendition != null) {
      return virtualRendition;
    }

    // no match found
    return null;
  }

  /**
   * Get merged list of file extensions from both media formats and media args.
   * @param mediaArgs Media args
   * @return Array of file extensions.
   *         Returns empty array if all file extensions are allowed.
   *         Returns null if different file extensions are requested in media formats and media args
   *         and the file extension filtering is not fulfillable.
   */
  private String[] getRequestedFileExtensions(MediaArgsType mediaArgs) {
    // get file extension defined in media args
    Set<String> mediaArgsFileExtensions = new HashSet<String>();
    if (mediaArgs.getFileExtensions() != null && mediaArgs.getFileExtensions().length > 0) {
      mediaArgsFileExtensions.addAll(ImmutableList.copyOf(mediaArgs.getFileExtensions()));
    }

    // get file extensions from media formats
    final Set<String> mediaFormatFileExtensions = new HashSet<String>();
    visitMediaFormats(mediaArgs, new MediaFormatVisitor<Object>() {

      @Override
      public Object visit(MediaFormat pMediaFormat) {
        if (pMediaFormat.getExtension() != null && pMediaFormat.getExtension().length > 0) {
          mediaFormatFileExtensions.addAll(ImmutableList.copyOf(pMediaFormat.getExtension()));
        }
        return null;
      }

    });

    // if extensions are defined both in mediaargs and media formats use intersection of both
    final String[] fileExtensions;
    if (mediaArgsFileExtensions.size() > 0 && mediaFormatFileExtensions.size() > 0) {
      Collection<String> intersection = Sets.intersection(mediaArgsFileExtensions, mediaFormatFileExtensions);
      if (intersection.size() == 0) {
        // not intersected file extensions - return null to singal no valid file extension request
        return null;
      }
      else {
        fileExtensions = intersection.toArray(new String[intersection.size()]);
      }
    }
    else if (mediaArgsFileExtensions.size() > 0) {
      fileExtensions = mediaArgsFileExtensions.toArray(new String[mediaArgsFileExtensions.size()]);
    }
    else {
      fileExtensions = mediaFormatFileExtensions.toArray(new String[mediaFormatFileExtensions.size()]);
    }

    return fileExtensions;
  }

  /**
   * Checks if the media args contain any with/height restriction, that means a rendition matching
   * the given size constraints is requested. Additionally it is checked that at least one image file
   * extension is requested.
   * @param mediaArgs Media arguments
   * @return true if any size restriction was defined.
   */
  private boolean isSizeMatchingRequest(MediaArgsType mediaArgs, String[] requestedFileExtensions) {

    // check that at least one image file extension is in the list of requested extensions
    boolean anyImageFileExtension = false;
    for (String fileExtension : requestedFileExtensions) {
      if (FileExtension.isImage(fileExtension)) {
        anyImageFileExtension = true;
      }
    }
    if (!anyImageFileExtension) {
      return false;
    }

    // check for size restriction
    if (mediaArgs.getFixedWidth() > 0 || mediaArgs.getFixedHeight() > 0) {
      return true;
    }
    Boolean isSizeMatchingMediaFormat = visitMediaFormats(mediaArgs, new MediaFormatVisitor<Boolean>() {

      @Override
      public Boolean visit(MediaFormat pMediaFormat) {
        if (pMediaFormat.getEffectiveWidthMin() > 0
            || pMediaFormat.getEffectiveWidthMax() > 0
            || pMediaFormat.getEffectiveHeightMin() > 0
            || pMediaFormat.getEffectiveHeightMax() > 0
            || pMediaFormat.getRatio() > 0) {
          return true;
        }
        return null;
      }
    });
    return isSizeMatchingMediaFormat != null && isSizeMatchingMediaFormat.booleanValue();
  }

  /**
   * Get rendition that matches exactly with the given media args requirements.
   * @param candidates Rendition candidates
   * @param mediaArgs Media args
   * @return Rendition or null if none found
   */
  private RenditionMetadata getExactMatchRendition(final Set<RenditionMetadata> candidates, MediaArgsType mediaArgs) {
    // check for fixed width and/or height request
    if (mediaArgs.getFixedWidth() > 0 || mediaArgs.getFixedHeight() > 0) {
      for (RenditionMetadata candidate : candidates) {
        if (candidate.matches(mediaArgs.getFixedWidth(), mediaArgs.getFixedHeight())) {
          return candidate;
        }
      }
    }

    // otherwise check for media format restriction
    else if (mediaArgs.getMediaFormats() != null && mediaArgs.getMediaFormats().length > 0) {
      return visitMediaFormats(mediaArgs, new MediaFormatVisitor<RenditionMetadata>() {

        @Override
        public RenditionMetadata visit(MediaFormat pMediaFormat) {
          for (RenditionMetadata candidate : candidates) {
            if (candidate.matches((int)pMediaFormat.getEffectiveWidthMin(),
                (int)pMediaFormat.getEffectiveHeightMin(),
                (int)pMediaFormat.getEffectiveWidthMax(),
                (int)pMediaFormat.getEffectiveHeightMax(),
                pMediaFormat.getRatio())) {
              return candidate;
            }
          }
          return null;
        }
      });
    }

    // no restriction - return original or first rendition
    else {
      return getOriginalOrFirstRendition(candidates);
    }

    // none found
    return null;
  }

  /**
   * Returns original rendition - if it is contained in the candidate set. Otherwise first candidate is returned.
   * @param pCandidates Candidates
   * @return Original or first rendition of candidates or null
   */
  private RenditionMetadata getOriginalOrFirstRendition(Set<RenditionMetadata> pCandidates) {
    if (this.originalRendition != null && pCandidates.contains(this.originalRendition)) {
      return this.originalRendition;
    }
    else if (pCandidates.size() > 0) {
      return pCandidates.iterator().next();
    }
    else {
      return null;
    }
  }

  /**
   * Check if a rendition is available from which the required format can be downscaled from and returns
   * a virtual rendition in this case.
   * @param pCandidates Candidates
   * @param pMediaArgs Media args
   * @return Rendition or null
   */
  private RenditionMetadata getVirtualRendition(final Set<RenditionMetadata> pCandidates, MediaArgsType pMediaArgs) {

    // get from fixed with/height
    if (pMediaArgs.getFixedWidth() > 0 || pMediaArgs.getFixedHeight() > 0) {
      int destWidth = pMediaArgs.getFixedWidth();
      int destHeight = pMediaArgs.getFixedHeight();
      double destRatio = 0;
      if (destWidth > 0 && destHeight > 0) {
        destRatio = (double)destWidth / (double)destHeight;
      }
      return getVirtualRendition(pCandidates, destWidth, destHeight, destRatio);
    }

    // or from any media format
    return visitMediaFormats(pMediaArgs, new MediaFormatVisitor<RenditionMetadata>() {

      @Override
      public RenditionMetadata visit(MediaFormat pMediaFormat) {
        int destWidth = (int)pMediaFormat.getEffectiveWidthMin();
        int destHeight = (int)pMediaFormat.getEffectiveHeightMin();
        double destRatio = pMediaFormat.getRatio();
        // try to find matching rendition, otherwise check for next media format
        return getVirtualRendition(pCandidates, destWidth, destHeight, destRatio);
      }
    });
  }

  /**
   * Check if a rendition is available from which the required format can be downscaled from and returns
   * a virtual rendition in this case.
   * @param pCandidates Candidates
   * @param pDestWidth Destination width
   * @param pDestHeight Destination height
   * @param pDestRatio Destination ratio
   * @return Rendition or null
   */
  private RenditionMetadata getVirtualRendition(Set<RenditionMetadata> pCandidates,
      int pDestWidth, int pDestHeight, double pDestRatio) {

    // if ratio is defined get first rendition with matching ratio and same or bigger size
    if (pDestRatio > 0) {
      for (RenditionMetadata candidate : pCandidates) {
        if (candidate.matches(pDestWidth, pDestHeight, 0, 0, pDestRatio)) {
          return getVirtualRendition(candidate, pDestWidth, pDestHeight, pDestRatio);
        }
      }
    }
    // otherwise get first rendition which is same or bigger in width and height
    else {
      for (RenditionMetadata candidate : pCandidates) {
        if (candidate.matches(pDestWidth, pDestHeight, 0, 0, 0d)) {
          return getVirtualRendition(candidate, pDestWidth, pDestHeight, 0d);
        }
      }
    }

    // none found
    return null;
  }

  /**
   * Get virtual rendition for given width/height/ratio.
   * @param pRendition Rendition
   * @param pWidth Width
   * @param pHeight Height
   * @param pRatio Ratio
   * @return Rendition or null
   */
  private RenditionMetadata getVirtualRendition(RenditionMetadata pRendition, int pWidth, int pHeight, double pRatio) {

    int width = pWidth;
    int height = pHeight;
    double ratio = pRatio;

    // if ratio is missing: calculate from given rendition
    if (ratio < MediaFormatHandler.RATIO_TOLERANCE) {
      ratio = (double)pRendition.getWidth() / (double)pRendition.getHeight();
    }

    // if height is missing - calculate from width
    if (height == 0 && width > 0) {
      height = (int)Math.round(width * ratio);
    }

    // if width is missing - calculate from height
    if (width == 0 && height > 0) {
      width = (int)Math.round(height / ratio);
    }

    // return virtual rendition
    if (pWidth > 0 && pHeight > 0) {
      if (pRendition instanceof VirtualCropRenditionMetadata) {
        VirtualCropRenditionMetadata cropRendition = (VirtualCropRenditionMetadata)pRendition;
        return new VirtualCropRenditionMetadata(cropRendition.getRendition(), pWidth, pHeight, cropRendition.getCropDimension());
      }
      else {
        return new VirtualRenditionMetadata(pRendition.getRendition(), pWidth, pHeight);
      }
    }
    else {
      return null;
    }
  }

  /**
   * Iterate over all media formats defined in media args. Ignores invalid media formats.
   * If the media format visitor returns a value that is not null, iteration is stopped and the value is returned from
   * this method.
   * @param pMediaArgs Media args
   * @param pMediaFormatVisitor Media format visitor
   * @return Return value form media format visitor, if any returned a value that is not null
   */
  private <T> T visitMediaFormats(MediaArgsType pMediaArgs, MediaFormatVisitor<T> pMediaFormatVisitor) {
    MediaFormatHandler mediaFormatHandler = AdaptTo.notNull(adaptable, MediaFormatHandler.class);
    String[] mediaFormats = pMediaArgs.getMediaFormats();
    if (mediaFormats != null) {
      for (String mediaFormatName : mediaFormats) {
        MediaFormat mediaFormat = mediaFormatHandler.getMediaFormat(mediaFormatName);
        if (mediaFormat != null) {
          T returnValue = pMediaFormatVisitor.visit(mediaFormat);
          if (returnValue != null) {
            return returnValue;
          }
        }
        else {
          mLog.debug("Media format is invalid, unable to resolve: {}", mediaFormatName);
        }
      }
    }
    return null;
  }

}
