package fr.acinq.eclair.swordfish.activity;

import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;

import fr.acinq.eclair.NodeParams;
import fr.acinq.eclair.swordfish.EclairHelper;
import fr.acinq.eclair.swordfish.R;
import fr.acinq.eclair.swordfish.customviews.DataRow;

public class SettingsActivity extends AppCompatActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_settings);

    Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar ab = getSupportActionBar();
    ab.setDisplayHomeAsUpEnabled(true);

    NodeParams np = EclairHelper.getInstance(getFilesDir()).getSetup().nodeParams();
    DataRow nodeRow = (DataRow) findViewById(R.id.settings_nodeid);
    nodeRow.setValue(np.privateKey().publicKey().toBin().toString());
    DataRow aliasRow = (DataRow) findViewById(R.id.settings_nodealias);
    aliasRow.setValue(np.alias());
  }
}
