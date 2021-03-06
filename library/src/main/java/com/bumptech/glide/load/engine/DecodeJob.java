package com.bumptech.glide.load.engine;

import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.util.Pools;
import android.util.Log;

import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.Registry;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.EncodeStrategy;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceEncoder;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.data.DataRewinder;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.resource.bitmap.Downsampler;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.pool.FactoryPools.Poolable;
import com.bumptech.glide.util.pool.GlideTrace;
import com.bumptech.glide.util.pool.StateVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 负责从缓存数据或原始源解码资源并应用转换和转码的类。
 * 注意：此类具有与equals不一致的自然顺序。
 * <p>
 * A class responsible for decoding resources either from cached data or from the original source
 * and applying transformations and transcodes.
 *
 * <p>Note: this class has a natural ordering that is inconsistent with equals.
 *
 * @param <R> The type of resource that will be transcoded from the decoded and transformed
 *            resource.
 */
class DecodeJob<R> implements DataFetcherGenerator.FetcherReadyCallback, Runnable,
        Comparable<DecodeJob<?>>, Poolable {
    private static final String TAG = "DecodeJob";

    // 从Glide注册的register中获取请求model 的加载器
    private final DecodeHelper<R> decodeHelper = new DecodeHelper<>();

    private final List<Throwable> throwables = new ArrayList<>();
    private final StateVerifier stateVerifier = StateVerifier.newInstance();
    private final DiskCacheProvider diskCacheProvider;
    private final Pools.Pool<DecodeJob<?>> pool;
    private final DeferredEncodeManager<?> deferredEncodeManager = new DeferredEncodeManager<>();
    private final ReleaseManager releaseManager = new ReleaseManager();

    private GlideContext glideContext;
    private Key signature;
    private Priority priority;
    private EngineKey loadKey;
    private int width;
    private int height;
    private DiskCacheStrategy diskCacheStrategy;
    private Options options;
    private Callback<R> callback;
    private int order;
    private Stage stage;
    private RunReason runReason;
    private long startFetchTime;
    private boolean onlyRetrieveFromCache;
    private Object model;

    private Thread currentThread;
    private Key currentSourceKey;
    private Key currentAttemptingKey;
    private Object currentData;
    private DataSource currentDataSource;
    private DataFetcher<?> currentFetcher;

    private volatile DataFetcherGenerator currentGenerator;
    private volatile boolean isCallbackNotified;
    private volatile boolean isCancelled;

    DecodeJob(DiskCacheProvider diskCacheProvider, Pools.Pool<DecodeJob<?>> pool) {
        this.diskCacheProvider = diskCacheProvider;
        this.pool = pool;
    }

    DecodeJob<R> init(
            GlideContext glideContext,
            Object model,
            EngineKey loadKey,
            Key signature,
            int width,
            int height,
            Class<?> resourceClass,
            Class<R> transcodeClass,
            Priority priority,
            DiskCacheStrategy diskCacheStrategy,
            Map<Class<?>, Transformation<?>> transformations,
            boolean isTransformationRequired,
            boolean isScaleOnlyOrNoTransform,
            boolean onlyRetrieveFromCache,
            Options options,
            Callback<R> callback,
            int order) {


        decodeHelper.init(
                glideContext,
                model,
                signature,
                width,
                height,
                diskCacheStrategy,
                resourceClass,
                transcodeClass,
                priority,
                options,
                transformations,
                isTransformationRequired,
                isScaleOnlyOrNoTransform,
                diskCacheProvider);


        this.glideContext = glideContext;
        this.signature = signature;
        this.priority = priority;
        this.loadKey = loadKey;
        this.width = width;
        this.height = height;
        this.diskCacheStrategy = diskCacheStrategy;
        this.onlyRetrieveFromCache = onlyRetrieveFromCache;
        this.options = options;
        this.callback = callback;
        this.order = order;
        this.runReason = RunReason.INITIALIZE;
        this.model = model;
        return this;
    }

    /**
     * Returns true if this job will attempt to decode a resource from the disk cache, and false if it
     * will always decode from source.
     */
    boolean willDecodeFromCache() {
        Stage firstStage = getNextStage(Stage.INITIALIZE);
        return firstStage == Stage.RESOURCE_CACHE || firstStage == Stage.DATA_CACHE;
    }

    /**
     * Called when this object is no longer in use externally.
     *
     * @param isRemovedFromQueue {@code true} if we've been removed from the queue and {@link #run} is
     *                           neither in progress nor will ever be called again.
     */
    void release(boolean isRemovedFromQueue) {
        if (releaseManager.release(isRemovedFromQueue)) {
            releaseInternal();
        }
    }

    /**
     * Called when we've finished encoding (either because the encode process is complete, or because
     * we don't have anything to encode).
     */
    private void onEncodeComplete() {
        if (releaseManager.onEncodeComplete()) {
            releaseInternal();
        }
    }

    /**
     * Called when the load has failed due to a an error or a series of errors.
     */
    private void onLoadFailed() {
        if (releaseManager.onFailed()) {
            releaseInternal();
        }
    }

    private void releaseInternal() {
        releaseManager.reset();
        deferredEncodeManager.clear();
        decodeHelper.clear();
        isCallbackNotified = false;
        glideContext = null;
        signature = null;
        options = null;
        priority = null;
        loadKey = null;
        callback = null;
        stage = null;
        currentGenerator = null;
        currentThread = null;
        currentSourceKey = null;
        currentData = null;
        currentDataSource = null;
        currentFetcher = null;
        startFetchTime = 0L;
        isCancelled = false;
        model = null;
        throwables.clear();
        pool.release(this);
    }

    @Override
    public int compareTo(@NonNull DecodeJob<?> other) {
        int result = getPriority() - other.getPriority();
        if (result == 0) {
            result = order - other.order;
        }
        return result;
    }

    private int getPriority() {
        return priority.ordinal();
    }

    public void cancel() {
        isCancelled = true;
        DataFetcherGenerator local = currentGenerator;
        if (local != null) {
            local.cancel();
        }
    }

    // We need to rethrow only CallbackException, but not other types of Throwables.
    @SuppressWarnings("PMD.AvoidRethrowingException")
    @Override
    public void run() {
        //执行网络
        DataFetcher<?> localFetcher = currentFetcher;
        try {
            if (isCancelled) {
                notifyFailed();
                return;
            }
            runWrapped();
        } catch (CallbackException e) {
            throw e;
        } catch (Throwable t) {
            if (stage != Stage.ENCODE) {
                throwables.add(t);
                notifyFailed();
            }
            if (!isCancelled) {
                throw t;
            }
            throw t;
        } finally {
            /**
             * 我们自定组件时回调cleanup（），如OkHttpStreamFetcher中
             *
             */
            if (localFetcher != null) {
                localFetcher.cleanup();
            }
        }
    }

    private void runWrapped() {
        switch (runReason) {
            case INITIALIZE:
                stage = getNextStage(Stage.INITIALIZE);
                currentGenerator = getNextGenerator();
                runGenerators();
                break;
            case SWITCH_TO_SOURCE_SERVICE:
                runGenerators();
                break;
            case DECODE_DATA://直接解码，然后返回解码后的数据
                decodeFromRetrievedData();
                break;
            default:
                throw new IllegalStateException("Unrecognized run reason: " + runReason);
        }
    }

    private DataFetcherGenerator getNextGenerator() {
        switch (stage) {
            case RESOURCE_CACHE:
                //资源磁盘缓存的执行者
                return new ResourceCacheGenerator(decodeHelper, this);
            case DATA_CACHE:
                // 源数据磁盘缓存的执行者
                return new DataCacheGenerator(decodeHelper, this);
            case SOURCE:
                // 无缓存、获取网络数据的执行者
                return new SourceGenerator(decodeHelper, this);
            case FINISHED:
                return null;
            default:
                throw new IllegalStateException("Unrecognized stage: " + stage);
        }
    }

    private void runGenerators() {
        boolean isStarted = false;
        /**
         *
         * 如果getNextGenerator（）返回SourceGenerator类，那么需要请求网络了
         * 开始网络请求图片
         *
         *
         * 调用DataFetcherGenerator的startNext方法执行请求，这里有可能是磁盘或者网络
         */
        while (!isCancelled && currentGenerator != null && !(isStarted = currentGenerator.startNext())) {
            stage = getNextStage(stage);
            currentGenerator = getNextGenerator();

            if (stage == Stage.SOURCE) {
                //重新调度startNext()
                reschedule();
                return;
            }
        }
        if ((stage == Stage.FINISHED || isCancelled) && !isStarted) {
            notifyFailed();
        }
    }

    private void notifyFailed() {
        setNotifiedOrThrow();
        GlideException e = new GlideException("Failed to load resource", new ArrayList<>(throwables));
        callback.onLoadFailed(e);
        onLoadFailed();
    }

    private void notifyComplete(Resource<R> resource, DataSource dataSource) {
        setNotifiedOrThrow();
        // 1.1 从 DecodeJob 的构建中, 我们知道这个 Callback 是一 EngineJob.onResourceReady()
        callback.onResourceReady(resource, dataSource);
    }

    private void setNotifiedOrThrow() {
        stateVerifier.throwIfRecycled();
        if (isCallbackNotified) {
            Throwable lastThrown = throwables.isEmpty() ? null : throwables.get(throwables.size() - 1);
            throw new IllegalStateException("Already notified", lastThrown);
        }
        isCallbackNotified = true;
    }

    private Stage getNextStage(Stage current) {
        switch (current) {
            case INITIALIZE:
                return diskCacheStrategy.decodeCachedResource()
                        ? Stage.RESOURCE_CACHE : getNextStage(Stage.RESOURCE_CACHE);
            case RESOURCE_CACHE:
                return diskCacheStrategy.decodeCachedData()
                        ? Stage.DATA_CACHE : getNextStage(Stage.DATA_CACHE);
            case DATA_CACHE:
                // Skip loading from source if the user opted to only retrieve the resource from cache.
                return onlyRetrieveFromCache ? Stage.FINISHED : Stage.SOURCE;
            case SOURCE:
            case FINISHED:
                return Stage.FINISHED;
            default:
                throw new IllegalArgumentException("Unrecognized stage: " + current);
        }
    }

    @Override
    public void reschedule() {
        runReason = RunReason.SWITCH_TO_SOURCE_SERVICE;
        callback.reschedule(this);
    }


    @Override
    public void onDataFetcherReady(Key sourceKey, Object data, DataFetcher<?> fetcher,
                                   DataSource dataSource, Key attemptedKey) {
        this.currentSourceKey = sourceKey;// 保存数据的 key
        this.currentData = data;// 保存数据实体
        this.currentFetcher = fetcher; // 保存数据的获取器
        this.currentDataSource = dataSource;// 数据来源: url 为 REMOTE 类型的枚举, 表示从远程获取
        this.currentAttemptingKey = attemptedKey;
        if (Thread.currentThread() != currentThread) {
            runReason = RunReason.DECODE_DATA;
            callback.reschedule(this);
        } else {
            GlideTrace.beginSection("DecodeJob.decodeFromRetrievedData");
            try {
                // 调用 decodeFromRetrievedData 解析获取的数据
                decodeFromRetrievedData();
            } finally {
                GlideTrace.endSection();
            }
        }
    }

    @Override
    public void onDataFetcherFailed(Key attemptedKey, Exception e, DataFetcher<?> fetcher,
                                    DataSource dataSource) {
        fetcher.cleanup();
        GlideException exception = new GlideException("Fetching data failed", e);
        exception.setLoggingDetails(attemptedKey, dataSource, fetcher.getDataClass());
        throwables.add(exception);
        if (Thread.currentThread() != currentThread) {
            runReason = RunReason.SWITCH_TO_SOURCE_SERVICE;
            callback.reschedule(this);
        } else {
            runGenerators();
        }
    }

    private void decodeFromRetrievedData() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Retrieved data", startFetchTime,
                    "data: " + currentData
                            + ", cache key: " + currentSourceKey
                            + ", fetcher: " + currentFetcher);
        }
        Resource<R> resource = null;
        try {
            // 1. 调用了 decodeFromData 获取资源
            resource = decodeFromData(currentFetcher, currentData, currentDataSource);
        } catch (GlideException e) {
            e.setLoggingDetails(currentAttemptingKey, currentDataSource);
            throwables.add(e);
        }
        if (resource != null) {
            // 2. 通知外界资源获取成功了
            notifyEncodeAndRelease(resource, currentDataSource);
        } else {
            runGenerators();
        }
    }

    private void notifyEncodeAndRelease(Resource<R> resource, DataSource dataSource) {
        if (resource instanceof Initializable) {
            ((Initializable) resource).initialize();
        }

        Resource<R> result = resource;
        LockedResource<R> lockedResource = null;
        if (deferredEncodeManager.hasResourceToEncode()) {
            lockedResource = LockedResource.obtain(resource);
            result = lockedResource;
        }
        // 1. 回调上层资源准备好了
        notifyComplete(result, dataSource);

        stage = Stage.ENCODE;
        try {
            // 2. 将数据缓存到磁盘
            if (deferredEncodeManager.hasResourceToEncode()) {
                deferredEncodeManager.encode(diskCacheProvider, options);
            }
        } finally {
            if (lockedResource != null) {
                lockedResource.unlock();
            }
        }
        // Call onEncodeComplete outside the finally block so that it's not called if the encode process
        // throws.
        onEncodeComplete();
    }

    private <Data> Resource<R> decodeFromData(DataFetcher<?> fetcher, Data data,
                                              DataSource dataSource) throws GlideException {
        try {
            if (data == null) {
                return null;
            }
            long startTime = LogTime.getLogTime();
            Resource<R> result = decodeFromFetcher(data, dataSource);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Decoded result " + result, startTime);
            }
            return result;
        } finally {
            fetcher.cleanup();
        }
    }

    @SuppressWarnings("unchecked")
    private <Data> Resource<R> decodeFromFetcher(Data data, DataSource dataSource)
            throws GlideException {
        LoadPath<Data, ?, R> path = decodeHelper.getLoadPath((Class<Data>) data.getClass());
        return runLoadPath(data, dataSource, path);
    }

    @NonNull
    private Options getOptionsWithHardwareConfig(DataSource dataSource) {
        Options options = this.options;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return options;
        }

        boolean isHardwareConfigSafe =
                dataSource == DataSource.RESOURCE_DISK_CACHE || decodeHelper.isScaleOnlyOrNoTransform();
        Boolean isHardwareConfigAllowed = options.get(Downsampler.ALLOW_HARDWARE_CONFIG);

        // If allow hardware config is defined, we can use it if it's set to false or if it's safe to
        // use the hardware config for the request.
        if (isHardwareConfigAllowed != null && (!isHardwareConfigAllowed || isHardwareConfigSafe)) {
            return options;
        }

        // If allow hardware config is undefined or is set to true but it's unsafe for us to use the
        // hardware config for this request, we need to override the config.
        options = new Options();
        options.putAll(this.options);
        options.set(Downsampler.ALLOW_HARDWARE_CONFIG, isHardwareConfigSafe);

        return options;
    }

    private <Data, ResourceType> Resource<R> runLoadPath(Data data, DataSource dataSource,
                                                         LoadPath<Data, ResourceType, R> path) throws GlideException {
        Options options = getOptionsWithHardwareConfig(dataSource);
        DataRewinder<Data> rewinder = glideContext.getRegistry().getRewinder(data);
        try {
            // ResourceType in DecodeCallback below is required for compilation to work with gradle.
            return path.load(
                    rewinder, options, width, height, new DecodeCallback<ResourceType>(dataSource));
        } finally {
            rewinder.cleanup();
        }
    }

    private void logWithTimeAndKey(String message, long startTime) {
        logWithTimeAndKey(message, startTime, null /*extraArgs*/);
    }

    private void logWithTimeAndKey(String message, long startTime, String extraArgs) {
        Log.v(TAG, message + " in " + LogTime.getElapsedMillis(startTime) + ", load key: " + loadKey
                + (extraArgs != null ? ", " + extraArgs : "") + ", thread: "
                + Thread.currentThread().getName());
    }

    @NonNull
    @Override
    public StateVerifier getVerifier() {
        return stateVerifier;
    }


    /**
     * 当我们将源数据解析成对应的资源之后, 便会调用 DecodeCallback.onResourceDecoded 处理资源
     *
     * @param dataSource
     * @param decoded
     * @param <Z>
     * @return
     */
    @Synthetic
    @NonNull
    <Z> Resource<Z> onResourceDecoded(DataSource dataSource,
                                      @NonNull Resource<Z> decoded) {
        @SuppressWarnings("unchecked")
        // 1. 获取数据资源的类型
                Class<Z> resourceSubClass = (Class<Z>) decoded.get().getClass();
        Transformation<Z> appliedTransformation = null;
        Resource<Z> transformed = decoded;


        // 2. 若非从资源磁盘缓存中获取的数据源, 则对资源进行 transformation 操作
        if (dataSource != DataSource.RESOURCE_DISK_CACHE) {
            appliedTransformation = decodeHelper.getTransformation(resourceSubClass);
            transformed = appliedTransformation.transform(glideContext, decoded, width, height);
        }
        // TODO: Make this the responsibility of the Transformation.
        if (!decoded.equals(transformed)) {
            decoded.recycle();
        }

        // 3. 构建数据编码的策略
        final EncodeStrategy encodeStrategy;
        final ResourceEncoder<Z> encoder;
        if (decodeHelper.isResourceEncoderAvailable(transformed)) {
            encoder = decodeHelper.getResultEncoder(transformed);
            encodeStrategy = encoder.getEncodeStrategy(options);
        } else {
            encoder = null;
            encodeStrategy = EncodeStrategy.NONE;
        }

        // 4. 根据编码策略, 构建缓存的 key
        Resource<Z> result = transformed;
        boolean isFromAlternateCacheKey = !decodeHelper.isSourceKey(currentSourceKey);
        if (diskCacheStrategy.isResourceCacheable(isFromAlternateCacheKey, dataSource,
                encodeStrategy)) {
            if (encoder == null) {
                throw new Registry.NoResultEncoderAvailableException(transformed.get().getClass());
            }
            final Key key;
            switch (encodeStrategy) {
                case SOURCE:
                    // 源数据的 key
                    key = new DataCacheKey(currentSourceKey, signature);
                    break;
                case TRANSFORMED:
                    // 资源数据的 key
                    key = new ResourceCacheKey(
                            decodeHelper.getArrayPool(),
                            currentSourceKey,
                            signature,
                            width,
                            height,
                            appliedTransformation,
                            resourceSubClass,
                            options);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown strategy: " + encodeStrategy);
            }


            // 5. 初始化编码管理者, 用于提交内存缓存
            LockedResource<Z> lockedResult = LockedResource.obtain(transformed);
            deferredEncodeManager.init(key, encoder, lockedResult);
            result = lockedResult;
        }
        // 返回 transform 之后的 bitmap
        return result;
    }

    private final class DecodeCallback<Z> implements DecodePath.DecodeCallback<Z> {

        private final DataSource dataSource;

        @Synthetic
        DecodeCallback(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @NonNull
        @Override
        public Resource<Z> onResourceDecoded(@NonNull Resource<Z> decoded) {
            return DecodeJob.this.onResourceDecoded(dataSource, decoded);
        }
    }

    /**
     * Responsible for indicating when it is safe for the job to be cleared and returned to the pool.
     */
    private static class ReleaseManager {
        private boolean isReleased;
        private boolean isEncodeComplete;
        private boolean isFailed;

        @Synthetic
        ReleaseManager() {
        }

        synchronized boolean release(boolean isRemovedFromQueue) {
            isReleased = true;
            return isComplete(isRemovedFromQueue);
        }

        synchronized boolean onEncodeComplete() {
            isEncodeComplete = true;
            return isComplete(false /*isRemovedFromQueue*/);
        }

        synchronized boolean onFailed() {
            isFailed = true;
            return isComplete(false /*isRemovedFromQueue*/);
        }

        synchronized void reset() {
            isEncodeComplete = false;
            isReleased = false;
            isFailed = false;
        }

        private boolean isComplete(boolean isRemovedFromQueue) {
            return (isFailed || isRemovedFromQueue || isEncodeComplete) && isReleased;
        }
    }

    /**
     * Allows transformed resources to be encoded after the transcoded result is already delivered to
     * * requestors.
     */
    private static class DeferredEncodeManager<Z> {
        private Key key;
        private ResourceEncoder<Z> encoder;
        private LockedResource<Z> toEncode;

        @Synthetic
        DeferredEncodeManager() {
        }

        // We just need the encoder and resource type to match, which this will enforce.
        @SuppressWarnings("unchecked")
        <X> void init(Key key, ResourceEncoder<X> encoder, LockedResource<X> toEncode) {
            this.key = key;
            this.encoder = (ResourceEncoder<Z>) encoder;
            this.toEncode = (LockedResource<Z>) toEncode;
        }

        void encode(DiskCacheProvider diskCacheProvider, Options options) {
            GlideTrace.beginSection("DecodeJob.encode");
            try {
                diskCacheProvider.getDiskCache().put(key, new DataCacheWriter<>(encoder, toEncode, options));
            } finally {
                toEncode.unlock();
                GlideTrace.endSection();
            }
        }

        boolean hasResourceToEncode() {
            return toEncode != null;
        }

        void clear() {
            key = null;
            encoder = null;
            toEncode = null;
        }
    }

    interface Callback<R> {

        void onResourceReady(Resource<R> resource, DataSource dataSource);

        void onLoadFailed(GlideException e);

        void reschedule(DecodeJob<?> job);
    }

    interface DiskCacheProvider {
        DiskCache getDiskCache();
    }

    /**
     * Why we're being executed again.
     */
    private enum RunReason {
        /**
         * 第一次提交
         * The first time we've been submitted.
         */
        INITIALIZE,
        /**
         * 我们希望从磁盘缓存服务切换到源执行程序
         * <p>
         * We want to switch from the disk cache service to the source executor.
         */
        SWITCH_TO_SOURCE_SERVICE,
        /**
         * We retrieved some data on a thread we don't own and want to switch back to our thread to
         * process the data.
         */
        DECODE_DATA,
    }

    /**
     * Where we're trying to decode data from.
     */
    private enum Stage {
        /**
         * The initial stage.
         */
        INITIALIZE,
        /**
         * Decode from a cached resource.
         */
        RESOURCE_CACHE,
        /**
         * Decode from cached source data.
         */
        DATA_CACHE,
        /**
         * Decode from retrieved source.
         */
        SOURCE,
        /**
         * Encoding transformed resources after a successful load.
         */
        ENCODE,
        /**
         * No more viable stages.
         */
        FINISHED,
    }
}
