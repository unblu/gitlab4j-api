package org.gitlab4j.api.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.gitlab4j.models.GitLabForm;
import org.gitlab4j.models.GitLabFormValue;
import org.junit.jupiter.api.Test;

public class TestMergeRequestParams {

    @Test
    public void testDraftPrefix() {
        MergeRequestParams params =
                new MergeRequestParams().withTitle("My Title").withDraft(true);

        GitLabForm form = params.getForm(true);
        Object titleValue = form.getFormValues().get("title").getValue();
        assertEquals("Draft: My Title", titleValue);
    }

    @Test
    public void testNoDraftPrefix() {
        MergeRequestParams params =
                new MergeRequestParams().withTitle("My Title").withDraft(false);

        GitLabForm form = params.getForm(true);
        Object titleValue = form.getFormValues().get("title").getValue();
        assertEquals("My Title", titleValue);
    }

    @Test
    public void testNullDraftPrefix() {
        MergeRequestParams params =
                new MergeRequestParams().withTitle("My Title").withDraft(null);

        GitLabForm form = params.getForm(true);
        Object titleValue = form.getFormValues().get("title").getValue();
        assertEquals("My Title", titleValue);
    }

    @Test
    public void testDraftWithNullTitle() {
        MergeRequestParams params = new MergeRequestParams().withTitle(null).withDraft(true);

        GitLabForm form = params.getForm(true);
        Object titleValue = form.getFormValues().get("title").getValue();
        assertEquals("Draft: ", titleValue);
    }

    @Test
    public void testNoDraftWithNullTitle() {
        MergeRequestParams params = new MergeRequestParams().withTitle(null).withDraft(false);

        GitLabForm form = params.getForm(true);
        GitLabFormValue titleFormValue = form.getFormValues().get("title");
        assertNotNull(titleFormValue);
        assertNull(titleFormValue.getValue());
    }
}
