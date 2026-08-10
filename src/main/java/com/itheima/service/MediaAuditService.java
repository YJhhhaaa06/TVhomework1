package com.itheima.service;

import com.itheima.config.AppConfig;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ParamException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.entity.ContentMedia;
import com.itheima.model.audit.MediaAuditItem;
import com.itheima.model.audit.MediaAuditResult;
import com.itheima.model.audit.RestoreResult;
import com.itheima.util.LogUtil;
import com.itheima.util.MyConnectionPool;
import jakarta.servlet.http.Part;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体资源运维：扫描 content_media 引用的文件是否存在，
 * 支持按数据库原文件名重新上传写回。
 */
@Component
public class MediaAuditService {

    private static final Logger LOGGER = LogUtil.getLogger(MediaAuditService.class);
    private static final Pattern MEDIA_URL_PATTERN =
            Pattern.compile("^/upload/(video|image|cover)/([A-Za-z0-9._-]+)$");

    @Inject
    private ContentDao contentDao;
    @Inject
    private ContentMediaDao contentMediaDao;

    /**
     * 全量扫描并回写 file_exists / last_verify_time。
     */
    public MediaAuditResult scanAll() {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            LocalDateTime now = LocalDateTime.now();
            Timestamp ts = Timestamp.valueOf(now);

            List<ContentMedia> mediaList = contentMediaDao.findAllMedia(conn);
            List<ContentCacheDTO> contents = contentDao.findAllContent(conn);
            Map<Long, String> titleMap = new HashMap<>();
            Set<Long> contentIdSet = new HashSet<>();
            for (ContentCacheDTO content : contents) {
                titleMap.put(content.getId(), content.getTitle());
                contentIdSet.add(content.getId());
            }

            List<MediaAuditItem> items = new ArrayList<>();
            Map<Long, List<Boolean>> mediaFlagsByContent = new HashMap<>();
            List<Long> orphanMediaIds = new ArrayList<>();
            int existing = 0;
            int missing = 0;
            int invalid = 0;

            for (ContentMedia media : mediaList) {
                String expectedPath = resolveExpectedPath(media.getUrl());
                boolean exists = expectedPath != null && new File(expectedPath).isFile();
                String status;
                if (expectedPath == null) {
                    status = "INVALID_URL";
                    invalid++;
                } else if (exists) {
                    status = "EXISTS";
                    existing++;
                } else {
                    status = "MISSING";
                    missing++;
                }

                contentMediaDao.updateFileExists(conn, media.getMediaId(), exists, ts);

                MediaAuditItem item = new MediaAuditItem();
                item.setMediaId(media.getMediaId());
                item.setContentId(media.getContentId());
                item.setContentTitle(titleMap.get(media.getContentId()));
                item.setType(media.getType());
                item.setUrl(media.getUrl());
                item.setFileExists(exists);
                item.setLastVerifyTime(now);
                item.setExpectedPath(expectedPath);
                item.setStatus(status);
                items.add(item);

                mediaFlagsByContent.computeIfAbsent(media.getContentId(), k -> new ArrayList<>()).add(exists);
                if (!contentIdSet.contains(media.getContentId())) {
                    orphanMediaIds.add(media.getMediaId());
                }
            }

            List<Long> contentsWithoutMedia = new ArrayList<>();
            for (Long contentId : contentIdSet) {
                List<Boolean> flags = mediaFlagsByContent.get(contentId);
                boolean ok;
                if (flags == null || flags.isEmpty()) {
                    contentsWithoutMedia.add(contentId);
                    ok = true; // 纯文字内容视为完整
                } else {
                    ok = true;
                    for (Boolean flag : flags) {
                        if (!flag) {
                            ok = false;
                            break;
                        }
                    }
                }
                contentDao.updateFileExists(conn, contentId, ok, ts);
            }

            MediaAuditResult result = new MediaAuditResult();
            result.setScanTime(now);
            result.setTotal(mediaList.size());
            result.setExisting(existing);
            result.setMissing(missing);
            result.setInvalid(invalid);
            result.setNotScanned(0);
            result.setOrphanMediaIds(orphanMediaIds);
            result.setContentsWithoutMedia(contentsWithoutMedia);
            result.setItems(items);
            return result;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "媒体扫描失败", e);
            throw new ServerException("媒体扫描失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    /**
     * 按数据库原文件名重新上传写回。
     */
    public RestoreResult restoreMedia(long mediaId, Part part) {
        if (part == null || part.getSize() <= 0) {
            throw new ParamException("请选择要恢复的文件");
        }

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            ContentMedia media = contentMediaDao.findMediaById(conn, mediaId);
            if (media == null) {
                throw new NotFoundException("媒体资源不存在: mediaId=" + mediaId);
            }

            String expectedPath = resolveExpectedPath(media.getUrl());
            if (expectedPath == null) {
                throw new ParamException("媒体 URL 不合法，无法定位文件: " + media.getUrl());
            }

            String targetExt = extensionOf(new File(expectedPath).getName());
            String submittedExt = extensionOf(part.getSubmittedFileName());
            if (submittedExt == null || !submittedExt.equalsIgnoreCase(targetExt)) {
                throw new ParamException("文件扩展名不匹配，目标需要 ." + targetExt);
            }

            File target = new File(expectedPath);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Files.copy(part.getInputStream(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            LocalDateTime now = LocalDateTime.now();
            Timestamp ts = Timestamp.valueOf(now);
            contentMediaDao.updateFileExists(conn, media.getMediaId(), true, ts);
            refreshContentAggregate(conn, media.getContentId(), ts);

            RestoreResult result = new RestoreResult();
            result.setMediaId(media.getMediaId());
            result.setUrl(media.getUrl());
            result.setTargetPath(target.getAbsolutePath().replace('\\', '/'));
            result.setSize(target.length());
            result.setFileExists(true);
            return result;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "媒体恢复写入失败, mediaId=" + mediaId, e);
            throw new ServerException("文件写入失败");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "媒体恢复更新数据库失败, mediaId=" + mediaId, e);
            throw new ServerException("数据库更新失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    private void refreshContentAggregate(Connection conn, long contentId, Timestamp ts) throws SQLException {
        if (!contentDao.isContentExist(conn, contentId)) {
            return; // 孤儿 media，不更新 content
        }
        Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, contentId);
        boolean hasMedia = false;
        boolean ok = true;
        for (List<ContentMedia> list : mediaMap.values()) {
            for (ContentMedia media : list) {
                hasMedia = true;
                String path = resolveExpectedPath(media.getUrl());
                if (path == null || !new File(path).isFile()) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                break;
            }
        }
        contentDao.updateFileExists(conn, contentId, !hasMedia || ok, ts);
    }

    private String resolveExpectedPath(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = MEDIA_URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            return null;
        }
        String type = matcher.group(1);
        String fileName = matcher.group(2);
        if (fileName.contains("..")) {
            return null;
        }
        return AppConfig.getUploadPath() + File.separator + type + File.separator + fileName;
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
