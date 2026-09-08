package net.kollnig.greasemilkyway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class OverviewAdapterTest {
    @Test
    public void recycledAppHolderResetsMissingAndServiceOffPresentation() {
        Context context = RuntimeEnvironment.getApplication();
        context.setTheme(R.style.Theme_GreaseMilkyway);
        ServiceConfig config = mock(ServiceConfig.class);
        when(config.getPackagePausedUntil(anyString())).thenReturn(0L);
        when(config.isPackageDisabled(anyString())).thenReturn(false);
        when(config.getRules()).thenReturn(new ArrayList<>());
        when(config.getNavigationRules()).thenReturn(new ArrayList<>());

        FilterRule installed = parse(context.getPackageName() +
                "##path=android.view.View[*]##comment=Hide feed").get(0);
        FilterRule missing = parse("com.example.not.installed##path=android.view.View[*]##comment=Hide feed").get(0);
        OverviewAdapter adapter = new OverviewAdapter(context, config, () -> { });
        List<FilterRule> rules = Arrays.asList(installed, missing);
        adapter.setRules(rules, true);

        RecyclerView parent = new RecyclerView(context);
        parent.setLayoutManager(new LinearLayoutManager(context));
        OverviewAdapter.AppHolder holder = (OverviewAdapter.AppHolder) adapter.onCreateViewHolder(
                parent, adapter.getItemViewType(1));
        adapter.onBindViewHolder(holder, 1);
        int activeBorder = holder.card.getStrokeColor();
        assertEquals(context.getResources().getColor(R.color.active_border), activeBorder);

        OverviewAdapter.AppHolder missingHeader = (OverviewAdapter.AppHolder) adapter.onCreateViewHolder(
                parent, adapter.getItemViewType(2));
        adapter.onBindViewHolder(missingHeader, 2);
        assertEquals(View.GONE, missingHeader.switchView.getVisibility());
        assertEquals(context.getResources().getColor(R.color.outline),
                missingHeader.card.getStrokeColor());

        missingHeader.itemView.performClick();
        adapter.setRules(rules, true);
        adapter.onBindViewHolder(holder, 3);
        assertEquals(View.GONE, holder.switchView.getVisibility());
        assertEquals(context.getResources().getColor(R.color.outline),
                holder.card.getStrokeColor());

        adapter.onBindViewHolder(holder, 1);
        assertEquals(View.VISIBLE, holder.switchView.getVisibility());
        assertEquals(activeBorder, holder.card.getStrokeColor());

        adapter.setRules(rules, false);
        adapter.onBindViewHolder(holder, 1);
        assertTrue(holder.switchView.isChecked());
        assertNotEquals(activeBorder, holder.card.getStrokeColor());
        assertEquals(context.getString(R.string.click_to_hide_elements),
                holder.subtitle.getText().toString());
    }

    private List<FilterRule> parse(String text) {
        return new FilterRuleParser().parseRules(new String[] {text});
    }

}
