#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <cctype>
#include <cstring>
#include <cstdio>
#include <string>
#include <vector>

// Only read a regular, app-owned snapshot through fixed, no-follow path components.
// Called by the module's WebUI shell, not by any browser.
int main(int argc, char** argv) {
    if (argc != 2 || getuid() != 0) return 2;
    std::string pkg(argv[1]);
    if (pkg.empty() || pkg.size() > 255 || pkg == "." || pkg == "..") return 2;
    for (unsigned char c : pkg)
        if (!(std::isalnum(c) || c == '.' || c == '_')) return 2;
    const int flags = O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW;
    // /data/user/0 is a platform-managed link on some Android versions.
    int root = open("/data/user/0", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (root < 0) return 3;
    int app = openat(root, pkg.c_str(), flags);
    close(root);
    if (app < 0) return 3;
    struct stat a{}, b{}, s{};
    if (fstat(app, &a) != 0 || a.st_uid < 10000) { close(app); return 3; }
    int files = openat(app, "files", flags);
    close(app);
    if (files < 0) return 3;
    if (fstat(files, &b) != 0 || b.st_uid != a.st_uid) { close(files); return 3; }
    int fd = openat(files, "chromext-local-scripts.json", O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    close(files);
    if (fd < 0) return 3;
    if (fstat(fd, &s) != 0 || !S_ISREG(s.st_mode) || s.st_uid != a.st_uid ||
        s.st_nlink != 1 || s.st_size <= 0 || s.st_size > 16*1024*1024) { close(fd); return 3; }
    std::vector<unsigned char> bytes(static_cast<size_t>(s.st_size));
    size_t off = 0;
    while (off < bytes.size()) {
        ssize_t n = read(fd, bytes.data()+off, bytes.size()-off);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { close(fd); return 3; }
        off += static_cast<size_t>(n);
    }
    close(fd);
    if (fwrite(bytes.data(), 1, bytes.size(), stdout) != bytes.size() || fflush(stdout)) return 4;
    return 0;
}
