package com.brandonbot.legalassistant.util;

import java.nio.file.Path;

public final class PathUtil {

    private PathUtil() {
    }

    public static String inferRegionLevel(Path path) {
        String full = path.toString();
        if (full.contains("国家法规")) {
            return "NATIONAL";
        }
        if (full.contains("江苏省政策法规")) {
            return "PROVINCIAL";
        }
        if (full.contains("扬州文件制度")) {
            return "MUNICIPAL";
        }
        return "UNKNOWN";
    }
}
