package com.fuzz.datacontroller.source;

import com.fuzz.datacontroller.DataController;
import com.fuzz.datacontroller.DataControllerRequest;
import com.fuzz.datacontroller.DataControllerResponse;
import com.fuzz.datacontroller.DataResponseError;

/**
 * Description:
 *
 * @author Andrew Grosner (fuzz)
 */
public class DataSource<T> {

    /**
     * Describes where this {@link DataSource} information came from. This is especially useful
     * when distinguishing whether information was loaded from network or storage.
     */
    public enum SourceType {

        /**
         * Retrieved from memory.
         */
        MEMORY,

        /**
         * Retrieved from disk.
         */
        DISK,

        /**
         * Retrieved from network.
         */
        NETWORK
    }

    public interface DataSourceStorage<T> {

        void store(DataControllerResponse<T> response);

        T getStoredData(SourceParams params);

        void clearStoredData(SourceParams params);

        boolean hasStoredData(SourceParams params);
    }


    public interface DataSourceCaller<T> {
        void get(SourceParams sourceParams,
                 DataController.Error error, DataController.Success<T> success);

        void cancel();
    }

    public interface Source<T> extends DataSourceStorage<T>, DataSourceCaller<T> {
    }

    /**
     * Description: A simple interface for determining when data should get refreshed. If the call
     * returns a false, a call to {@link #get(SourceParams, DataController.Success, DataController.Error)}
     * does not do anything.
     */
    public interface RefreshStrategy<TResponse> {

        /**
         * @param dataSource The data source that we're calling.
         * @return True if we should refresh by calling {@link #get(SourceParams,
         * DataController.Success, DataController.Error)}.
         * If false, we do not refresh data.
         */
        boolean shouldRefresh(DataSource<TResponse> dataSource);
    }

    private Object syncLock;

    private boolean isBusy = false;

    private final DataSourceCaller<T> caller;
    private final SourceType sourceType;

    private final DataSourceStorage<T> storage;
    private final RefreshStrategy<T> refreshStrategy;
    private final SourceParams defaultParams;


    DataSource(Builder<T> builder) {
        this.caller = builder.caller;
        this.sourceType = builder.sourceType;
        this.storage = builder.storage;
        if (builder.refreshStrategy != null) {
            this.refreshStrategy = builder.refreshStrategy;
        } else {
            this.refreshStrategy = new DefaultRefreshStrategy();
        }
        this.defaultParams = builder.defaultParams;
    }

    public RefreshStrategy<T> refreshStrategy() {
        return refreshStrategy;
    }

    public SourceType sourceType() {
        return sourceType;
    }

    public T getStoredData() {
        return storage != null ? storage.getStoredData(defaultParams) : null;
    }

    public T getStoredData(SourceParams sourceParams) {
        return storage != null ? storage.getStoredData(sourceParams) : null;
    }

    public boolean hasStoredData() {
        return storage.hasStoredData(defaultParams);
    }

    public boolean hasStoredData(SourceParams params) {
        return storage.hasStoredData(params);
    }

    public void clearStoredData() {
        storage.clearStoredData(defaultParams);
    }

    public void clearStoredData(SourceParams sourceParams) {
        if (storage != null) {
            storage.clearStoredData(sourceParams);
        }
    }

    /**
     * Calls {@link DataSourceStorage#store(DataControllerResponse)}
     * only if the {@link DataControllerResponse#getSourceType()} is different.
     * i.e comes from a different source.
     *
     * @param tResponse The response returned here.
     */
    public final void store(DataControllerResponse<T> tResponse) {
        if (!tResponse.getSourceType().equals(sourceType)
                && storage != null) {
            storage.store(tResponse);
        }
    }

    /**
     * Requests a call on the underlying data to return on the specified success and error callbacks. This
     * respects the {@link RefreshStrategy} set in the constructor of this source.
     * It is explicitly up to the source on what kinds of parameters it can handle or expect. The expectation
     * is that if no params returned or it is a default instance, all data should be returned.
     *
     * @param sourceParams The params used to retrieve information from the {@link DataSource}.
     * @param success      Called when a successful request returns.
     * @param error        Called when a request fails.
     */
    public final void get(SourceParams sourceParams, DataController.Success<T> success,
                          DataController.Error error) {
        if (refreshStrategy().shouldRefresh(this) && !isBusy()
                || sourceParams != null && sourceParams.force) {
            setBusy(true);
            SourceParams params = sourceParams;
            if (params == null || SourceParams.defaultParams.equals(params)) {
                params = defaultParams;
            }

            if (caller != null) {
                caller.get(params, wrapBusyError(error), wrapBusySuccess(success));
            }
        }
    }

    public void cancel() {
        caller.cancel();
    }

    private void setBusy(boolean isBusy) {
        synchronized (syncLock()) {
            this.isBusy = isBusy;
        }
    }

    public boolean isBusy() {
        synchronized (syncLock()) {
            return isBusy;
        }
    }

    private Object syncLock() {
        if (syncLock == null) {
            syncLock = new Object();
        }
        return syncLock;
    }

    /**
     * @return convenience method designed to communicate busy state completion.
     */
    private DataController.Error wrapBusyError(final DataController.Error error) {
        return new DataController.Error() {
            @Override
            public void onFailure(DataResponseError dataResponseError) {
                setBusy(false);
                error.onFailure(dataResponseError);
            }
        };
    }

    /**
     * @return convenience method designed to communicate busy state completion.
     */
    private DataController.Success<T> wrapBusySuccess(
            final DataController.Success<T> success) {
        return new DataController.Success<T>() {
            @Override
            public void onSuccess(DataControllerResponse<T> response) {
                setBusy(false);
                success.onSuccess(response);
            }
        };
    }

    public static final class Builder<T> {

        private final DataSourceCaller<T> caller;
        private final SourceType sourceType;

        private DataSourceStorage<T> storage;
        private RefreshStrategy<T> refreshStrategy;

        private SourceParams defaultParams = SourceParams.defaultParams;

        public Builder(DataSourceCaller<T> caller,
                       SourceType sourceType) {
            this.caller = caller;
            this.sourceType = sourceType;
        }

        public Builder<T> storage(DataSourceStorage<T> storage) {
            this.storage = storage;
            return this;
        }

        public Builder<T> refreshStrategy(RefreshStrategy<T> strategy) {
            this.refreshStrategy = strategy;
            return this;
        }

        public Builder<T> defaultParams(SourceParams defaultParams) {
            this.defaultParams = defaultParams;
            return this;
        }

        public DataSource<T> build() {
            return new DataSource<>(this);
        }
    }

    /**
     * SourceParams provide the base class for all information passing between
     * caller {@link DataControllerRequest} and receiver {@link DataSource}.
     * <p></p>
     * Some {@link DataSource} require more information that this base class cannot represent. It
     * is up to them to provide the kind of params they expect.
     */
    public static class SourceParams {

        public static final SourceParams defaultParams = new SourceParams();

        /**
         * Data in this class.
         */
        public Object data;

        /**
         * If true, we force a refresh to happen.
         */
        public boolean force;
    }

    private final class DefaultRefreshStrategy implements RefreshStrategy<T> {

        @Override
        public boolean shouldRefresh(DataSource<T> dataSource) {
            return true;
        }
    }
}
