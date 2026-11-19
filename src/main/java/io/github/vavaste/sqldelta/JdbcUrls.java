package io.github.vavaste.sqldelta;

final class JdbcUrls {

    static String sanitize(String url) {
        String result = url.strip().replaceFirst("[?;].*", "");
        int slashes = result.indexOf("//");
        if (slashes < 0) {
            return result;
        }
        int authorityStart = slashes + 2;
        int authorityEnd = result.indexOf('/', authorityStart);
        // only an '@' inside the authority separates user info: a path such as /builds/job@2/db keeps its '@'
        int at = result.lastIndexOf('@', authorityEnd < 0 ? result.length() : authorityEnd - 1);
        if (at < authorityStart) {
            return result;
        }
        return result.substring(0, authorityStart) + result.substring(at + 1);
    }
}
