package org.gitlab4j.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.gitlab4j.api.utils.JacksonJson;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <p>This class defines an Iterator implementation that is used as a paging iterator for all API methods that
 * return a List of objects.  It hides the details of interacting with the GitLab API when paging is involved
 * simplifying accessing large lists of objects.</p>
 *
 * <p>Example usage:</p>
 *
 * <pre>
 *   // Get a Pager instance that will page through the projects with 10 projects per page
 *   Pager&lt;Project&gt; projectPager = gitlabApi.getProjectsApi().getProjectsPager(10);
 *
 *   // Iterate through the pages and print out the name and description
 *   while (projectsPager.hasNext())) {
 *       List&lt;Project&gt; projects = projectsPager.next();
 *       for (Project project : projects) {
 *           System.out.println(project.getName() + " : " + project.getDescription());
 *       }
 *   }
 * </pre>
 *
 * @param <T> the GitLab4J type contained in the List.
 */
public class Pager<T> implements Iterator<List<T>>, Constants {

    private int itemsPerPage;
    private int totalPages;
    private int totalItems;
    private int currentPage = 0;
    private int kaminariNextPage;

    private Stream<T> pagerStream = null;

    private AbstractApi api;
    private MultivaluedMap<String, String> queryParams;
    private Object[] pathArgs;

    private static JacksonJson jacksonJson = new JacksonJson();
    private static ObjectMapper mapper = jacksonJson.getObjectMapper();
    private JavaType javaType;

    private Map<Integer, Page> pages = new HashMap<>();

    /**
     * Creates a Pager instance to access the API through the specified path and query parameters.
     *
     * @param api the AbstractApi implementation to communicate through
     * @param type the GitLab4J type that will be contained in the List
     * @param itemsPerPage items per page
     * @param queryParams HTTP query params
     * @param pathArgs HTTP path arguments
     * @throws GitLabApiException if any error occurs
     */
    public Pager(AbstractApi api, Class<T> type, int itemsPerPage, MultivaluedMap<String, String> queryParams, Object... pathArgs) throws GitLabApiException {
        this.api = api;
        javaType = mapper.getTypeFactory().constructCollectionType(List.class, type);

        if (itemsPerPage < 1) {
            itemsPerPage = api.getDefaultPerPage();
        }

        // Make sure the per_page parameter is present
        if (queryParams == null) {
            queryParams = new GitLabApiForm().withParam(PER_PAGE_PARAM, itemsPerPage).asMap();
        } else {
            queryParams.remove(PER_PAGE_PARAM);
            queryParams.add(PER_PAGE_PARAM, Integer.toString(itemsPerPage));
        }
        this.queryParams = queryParams;
        this.pathArgs = pathArgs;



        Page page = new Page(1);
        pages.put(page.getPageNumber(), page);

        Response response = fetchPage(page);

        if (page.items == null) {
            throw new GitLabApiException("Invalid response from from GitLab server");
        }




        this.itemsPerPage = getIntHeaderValue(response, PER_PAGE);

        // Some API endpoints do not return the "X-Per-Page" header when there is only 1 page, check for that condition and act accordingly
        if (this.itemsPerPage == -1) {
            this.itemsPerPage = itemsPerPage;
            totalPages = 1;
            totalItems = page.items.size();
            return;
        }

        totalPages = getIntHeaderValue(response, TOTAL_PAGES_HEADER);
        totalItems = getIntHeaderValue(response, TOTAL_HEADER);

        // Since GitLab 11.8 and behind the api_kaminari_count_with_limit feature flag,
        // if the number of resources is more than 10,000, the X-Total and X-Total-Page
        // headers as well as the rel="last" Link are not present in the response headers.
        if (totalPages == -1 || totalItems == -1) {

            int nextPage = getIntHeaderValue(response, NEXT_PAGE_HEADER);
            if (nextPage < 2) {
                totalPages = 1;
                totalItems = page.items.size();
            } else {
                kaminariNextPage = 2;
            }
        }
     }

    /**
     * Get the specified header value from the Response instance.
     *
     * @param response the Response instance to get the value from
     * @param key the HTTP header key to get the value for
     * @return the specified header value from the Response instance, or null if the header is not present
     * @throws GitLabApiException if any error occurs
     */
    private String getHeaderValue(Response response, String key) throws GitLabApiException {

        String value = response.getHeaderString(key);
        value = (value != null ? value.trim() : null);
        if (value == null || value.length() == 0) {
            return (null);
        }

        return (value);
    }

    /**
     * Get the specified integer header value from the Response instance.
     *
     * @param response the Response instance to get the value from
     * @param key the HTTP header key to get the value for
     * @return the specified integer header value from the Response instance, or -1 if the header is not present
     * @throws GitLabApiException if any error occurs
     */
    private int getIntHeaderValue(Response response, String key) throws GitLabApiException {

        String value = getHeaderValue(response, key);
        if (value == null) {
            return -1;
        }

        try {
            return (Integer.parseInt(value));
        } catch (NumberFormatException nfe) {
            throw new GitLabApiException("Invalid '" + key + "' header value (" + value + ") from server");
        }
    }

    /**
     * Get the items per page value.
     *
     * @return the items per page value
     */
    public int getItemsPerPage() {
        return (itemsPerPage);
    }

    /**
     * Get the total number of pages returned by the GitLab API.
     *
     * @return the total number of pages returned by the GitLab API, or -1 if the Kaminari limit of 10,000 has been exceeded
     */
    public int getTotalPages() {
        return (totalPages);
    }

    /**
     * Get the total number of items (T instances) returned by the GitLab API.
     *
     * @return the total number of items (T instances) returned by the GitLab API, or -1 if the Kaminari limit of 10,000 has been exceeded
     */
    public int getTotalItems() {
        return (totalItems);
    }

    /**
     * Get the current page of the iteration.
     *
     * @return the current page of the iteration
     */
    public int getCurrentPage() {
        return (currentPage);
    }

    /**
     * Returns the true if there are additional pages to iterate over, otherwise returns false.
     *
     * @return true if there are additional pages to iterate over, otherwise returns false
     */
    @Override
    public boolean hasNext() {
        return (currentPage < totalPages || currentPage < kaminariNextPage);
    }

    /**
     * Returns the next List in the iteration containing the next page of objects.
     *
     * @return the next List in the iteration
     * @throws NoSuchElementException if the iteration has no more elements
     * @throws RuntimeException if a GitLab API error occurs, will contain a wrapped GitLabApiException with the details of the error
     */
    @Override
    public List<T> next() {
        return (page(currentPage + 1));
    }

    /**
     * This method is not implemented and will throw an UnsupportedOperationException if called.
     *
     * @throws UnsupportedOperationException when invoked
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the first page of List. Will rewind the iterator.
     *
     * @return the first page of List
     */
    public List<T> first() {
        return (page(1));
    }

    /**
     * Returns the last page of List. Will set the iterator to the end.
     *
     * @return the last page of List
     * @throws GitLabApiException if any error occurs
     */
    public List<T> last() throws GitLabApiException {

        if (kaminariNextPage != 0) {
            throw new GitLabApiException("Kaminari count limit exceeded, unable to fetch last page");
        }

        return (page(totalPages));
    }

    /**
     * Returns the previous page of List. Will set the iterator to the previous page.
     *
     * @return the previous page of List
     */
    public List<T> previous() {
        return (page(currentPage - 1));
    }

    /**
     * Returns the current page of List.
     *
     * @return the current page of List
     */
    public List<T> current() {
        return (page(currentPage));
    }

    /**
     * Returns the specified page of List.
     *
     * @param pageNumber the page to get
     * @return the specified page of List
     * @throws NoSuchElementException if the iteration has no more elements
     * @throws RuntimeException if a GitLab API error occurs, will contain a wrapped GitLabApiException with the details of the error
     */
    public List<T> page(int pageNumber) {

        if (currentPage == 0 && pageNumber == 1) {
            currentPage = 1;
        }

        if (pageNumber > totalPages && pageNumber > kaminariNextPage) {
            throw new NoSuchElementException();
        } else if (pageNumber < 1) {
            throw new NoSuchElementException();
        }

        try {
            Page page = pages.get(pageNumber);
            synchronized (this) {
                page = pages.get(pageNumber);
                if (page == null) {
                    page = new Page(pageNumber);
                    pages.put(page.pageNumber, page);
                }
            }

            synchronized (page) {
                if (page.items != null) {
                    return page.items;
                }
            }

            Response response = fetchPage(page);
            currentPage = pageNumber;

            if (kaminariNextPage > 0) {
                kaminariNextPage = getIntHeaderValue(response, NEXT_PAGE_HEADER);
            }

            return (page.items);

        } catch (GitLabApiException e) {
            throw new RuntimeException(e);
        }
    }

    private Response fetchPage(Page page) throws GitLabApiException {
        synchronized (page) {
            if (page.items != null) throw new RuntimeException("page already fetched");

            MultivaluedMap<String, String> effectiveQueryParams = new GitLabApiForm().asMap();
            effectiveQueryParams.putAll(queryParams);
            List<String> pageParam = new ArrayList<>();
            pageParam.add(Integer.toString(page.pageNumber));
            effectiveQueryParams.put(PAGE_PARAM, pageParam);

            Response response = api.get(Response.Status.OK, effectiveQueryParams, pathArgs);

            try {
                page.setItems(mapper.readValue((InputStream) response.getEntity(), javaType));
            } catch (Exception e) {
                throw new GitLabApiException(e);
            }
            return response;
        }
    }

    /**
     * Gets all the items from each page as a single List instance.
     *
     * @return all the items from each page as a single List instance
     * @throws GitLabApiException if any error occurs
     */
    public List<T> all() throws GitLabApiException {
        return all(api.gitLabApi.getDefaultPageFetchParallel());
    }

    /**
     * Gets all the items from each page as a single List instance.
     * @param parallel number of parallel fetches to use
     * @return all the items from each page as a single List instance
     * @throws GitLabApiException if any error occurs
     */
    public List<T> all(int parallel) throws GitLabApiException {
        if (parallel < 2) {
            return fetchAllSynchronously();
        } else {
            return fetchAllParallel(parallel);
        }
    }

    private List<T> fetchAllParallel(int parallel) throws GitLabApiException {
        List<T> allItems = new ArrayList<>(Math.max(totalItems, 0));
        ParallelTaskExecutor taskExecutor = api.gitLabApi.getParallelTaskExecutor();
        if (taskExecutor == null) {
            throw new IllegalStateException("no parallel task executor set, cannot fetch pages in parallel");
        }

        List<Callable<List<T>>> tasks = new ArrayList<>();
        for(int i=1; i<=totalPages; i++) {
            final int pageNumber = i;
            tasks.add(() -> {
                return page(pageNumber);
            });
        }
        try {
            List<List<T>> results = taskExecutor.execute(tasks);
            for(List<T> items : results) {
                allItems.addAll(items);
            }
            return allItems;
        } catch (Exception e) {
            if (e instanceof GitLabApiException) {
                throw (GitLabApiException) e;
            }
            throw new GitLabApiException(e);
        }
    }

    private List<T> fetchAllSynchronously() {
        List<T> allItems = new ArrayList<>(Math.max(totalItems, 0));
        currentPage = 0;


        // Iterate through the pages and append each page of items to the list
        while (hasNext()) {
            allItems.addAll(next());
        }
        return allItems;
    }

    /**
     * Builds and returns a Stream instance which is pre-populated with all items from all pages.
     *
     * @return a Stream instance which is pre-populated with all items from all pages
     * @throws IllegalStateException if Stream has already been issued
     * @throws GitLabApiException if any other error occurs
     */
    public Stream<T> stream() throws GitLabApiException, IllegalStateException {

        if (pagerStream == null) {
            synchronized (this) {
                if (pagerStream == null) {

                    // Make sure that current page is 0, this will ensure the whole list is streamed
                    // regardless of what page the instance is currently on.
                    currentPage = 0;

                    // Create a Stream.Builder to contain all the items. This is more efficient than
                    // getting a List with all() and streaming that List
                    Stream.Builder<T> streamBuilder = Stream.builder();

                    // Iterate through the pages and append each page of items to the stream builder
                    while (hasNext()) {
                        next().forEach(streamBuilder);
                    }

                    pagerStream = streamBuilder.build();
                    return (pagerStream);
                }
            }
        }

        throw new IllegalStateException("Stream already issued");
    }

    /**
     * Creates a Stream instance for lazily streaming items from the GitLab server.
     *
     * @return a Stream instance for lazily streaming items from the GitLab server
     * @throws IllegalStateException if Stream has already been issued
     */
    public Stream<T> lazyStream() throws IllegalStateException {

        if (pagerStream == null) {
            synchronized (this) {
                if (pagerStream == null) {

                    // Make sure that current page is 0, this will ensure the whole list is streamed
                    // regardless of what page the instance is currently on.
                    currentPage = 0;

                    pagerStream = StreamSupport.stream(new PagerSpliterator<T>(this), false);
                    return (pagerStream);
                }
            }
        }

        throw new IllegalStateException("Stream already issued");
    }


    private class Page {
        private Integer pageNumber;
        private List<T> items;

        Page(Integer pageNumber) {
            this.pageNumber = pageNumber;
        }

        void setItems(List<T> items) {
            this.items = items;
        }

        public List<T> getItems() {
            return items;
        }

        Integer getPageNumber() {
            return pageNumber;
        }
    }
}
