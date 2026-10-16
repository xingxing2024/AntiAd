package cn.server.antiad;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Paper 插件加载器：只干一件事 —— 把 SQLite 驱动拉进本插件的类路径。
 *
 * <p>驱动不打进 jar（否则要带上 23 个平台的原生库，12MB），改由服务端下载。
 * 默认的官方源（maven-central.storage-download.googleapis.com）在国内经常几 KB/s 甚至超时，
 * 所以这里主动指定仓库顺序：
 * <ol>
 *   <li>服务端自己配的镜像（启动参数 {@code -Dorg.bukkit.plugin.java.LibraryLoader.centralURL=…}
 *       或环境变量 {@code PAPER_DEFAULT_CENTRAL_REPOSITORY}）</li>
 *   <li>阿里云公共仓库（国内直连，默认）</li>
 *   <li>官方默认镜像（兜底，阿里云挂了才走）</li>
 * </ol>
 * 解析结果缓存在服务端根目录的 {@code libraries/} 里，与 {@code plugin.yml} 的 libraries 机制共用同一份缓存，
 * 所以同一台机器上别的插件下过的同一个驱动不会被重复下载（仓库 id 统一用 {@code central} 就是为了命中这份缓存）。
 */
public final class AntiAdLoader implements PluginLoader {
    /** 驱动坐标：与 pom.xml 里的 sqlite-jdbc 版本保持一致。 */
    static final String DRIVER = "org.xerial:sqlite-jdbc:3.51.0.0";
    /** 国内镜像（阿里云公共仓库，代理 Maven Central）。 */
    static final String MIRROR_ALIYUN = "https://maven.aliyun.com/repository/public";
    /** 兜底：Paper 官方镜像。 */
    static final String MIRROR_PAPER = "https://repo.papermc.io/repository/maven-public/";
    /** 服务端自己指定镜像时用的两个开关。 */
    static final String MIRROR_PROPERTY = "org.bukkit.plugin.java.LibraryLoader.centralURL";
    static final String MIRROR_ENV = "PAPER_DEFAULT_CENTRAL_REPOSITORY";

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        // 仓库 id 用 central：服务端 libraries/ 里的缓存是按仓库 id 记账的，id 一致才会直接命中缓存。
        resolver.addRepository(new RemoteRepository.Builder("central", "default", centralUrl()).build());
        resolver.addRepository(new RemoteRepository.Builder("papermc", "default", MIRROR_PAPER).build());
        resolver.addDependency(new Dependency(new DefaultArtifact(DRIVER), null));
        classpathBuilder.addLibrary(resolver);
    }

    /** 服务端配了镜像就听服务端的，没配就用阿里云。 */
    static String centralUrl() {
        String configured = System.getProperty(MIRROR_PROPERTY);
        if (configured == null || configured.isBlank()) configured = System.getenv(MIRROR_ENV);
        return configured == null || configured.isBlank() ? MIRROR_ALIYUN : configured.strip();
    }
}
