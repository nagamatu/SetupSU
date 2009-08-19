package com.gmail.nagamatu.setupsu;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class main extends Activity implements Runnable {
	static final String TAG = "SetupSU";
	static final String ASSET_RAW = "raw";
	static final String asroot_name = "asroot";
	static final String busybox_name = "busybox";
	static final String su_name = "su";
    static final String[] assetFiles;

	static final Runtime runtime = Runtime.getRuntime();
	static final int DEFAULT_BUFFER_SIZE = 1024 * 4;
	
	ExecThread background;
	private final Handler handler = new Handler();
	private ProgressDialog dialog;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        Button btn = (Button) findViewById(R.id.ButtonProceed);        
        btn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				TextView tv = (TextView)main.this.findViewById(R.id.message);
				tv.setText("");

		        dialog = new ProgressDialog(main.this);
		        dialog.setIndeterminate(true);
		        dialog.setMessage(main.this.getResources().getString(R.string.progress_message));
		        dialog.show();

				background = new ExecThread(handler, main.this);
				background.start();
			}
        });
    }

    private class ExecThread extends Thread {
    	private Handler handler;
    	private final Runnable listener;
    	
    	public ExecThread(Handler handler, Runnable listener) {
    		this.handler = handler;
    		this.listener = listener;
    	}
    	public void run() {
    		try {
				copyAndExec();
			} catch (IOException e) {
				e.printStackTrace();
			}
    		handler.post(listener);
    	}
    }

    private void extractFromAsset(AssetManager as, String name) throws IOException {
    	Log.d(TAG, "extractFromAsset: " + name);
  
    	FileOutputStream output = null;
    	byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
    	int n;

    	try {
    		output = openFileOutput(name, Context.MODE_PRIVATE);    		
    	} catch (IOException e) {
    		e.printStackTrace();
    		throw e;
    	}
    	
    	InputStream is = null;
    	try {
    		is = as.open(ASSET_RAW + "/" + name);
    		while (-1 != (n = is.read(buffer))) {
    			output.write(buffer, 0, n);
    		}
    		output.flush();
    		is.close();
    	} catch (IOException e) {
    
    		try {
    	    	for (int i = 0; ; i++) {
    	    		is = as.open(ASSET_RAW + "/" + name + "." + String.valueOf(i));
    	    		while (-1 != (n = is.read(buffer))) {
    	    			output.write(buffer, 0, n);
    	    		}
    	    		output.flush();
    	    		is.close();
    	    	}
    		} catch (IOException _e) {}
    	} finally {
    		if (output != null) {
    			output.close();
    		}
    	}

		changePermission(getFileStreamPath(name).getAbsolutePath(), 0755);
    }

    private void changePermission(String file, int mode) {
		Log.d(TAG, "changePermission: " + file);

		try {
			final String[] cmds = new String[] {
					"/system/bin/chmod",
					String.format("%o", mode),
					file
			};
			Process p = runtime.exec(cmds);
			try {
				if (p.waitFor() != 0) {
					Log.d(TAG, "changePermission: chmod returns " + p.exitValue());
				}
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
			//p.destroy();
		} catch (IOException e) {
			Log.e(TAG, "changePermission: " + e.toString());
		}
    }
    
    private class MessageListener implements Runnable {
    	private String message;

    	public MessageListener(String message) {
    		this.message = message;
    	}

		public void run() {
			TextView tv = (TextView)main.this.findViewById(R.id.message);
			tv.append(message);
			tv.append("\n");
		}
    	
    }

	private final void copyAndExec() throws IOException {
		AssetManager as = getResources().getAssets();		
		for (int i = 0; i < assetFiles.length; i++) {
			extractFromAsset(as, assetFiles[i]);
			handler.post(new MessageListener("# extract " + assetFiles[i]));
		}

		String[] script = new String[] {
			"/system/bin/mount -r -w -o remount /dev/block/mtdblock3 /system",
			"/system/bin/mkdir /system/xbin",
			getFileStreamPath(busybox_name).getAbsolutePath() + " cp " + getFileStreamPath(su_name).getAbsolutePath() + " /system/xbin/su",
			"/system/bin/chmod 04755 /system/xbin/su",
			getFileStreamPath(busybox_name).getAbsoluteFile() + " cp " + getFileStreamPath(busybox_name).getAbsolutePath() + " /system/xbin/busybox",
			"/system/bin/mount -r -o remount /dev/block/mtdblock3 /system",
		};

		for (int i = 0; i < script.length; i++) {
			if (0 != suexec(script[i])) {
				Log.e(TAG, "suecec: fails: " + script[i]);
				if (i != 1)	// mkdir may fails
					break;
			}
		}		
	}

	private final int suexec(String cmdline) throws IOException {
		File tmpfile = File.createTempFile("suexec", ".dat", getCacheDir());
		tmpfile.delete();

		Log.v(TAG, "suexec: " + cmdline);
		handler.post(new MessageListener("# " + cmdline));
		
		String asroot_path = getFileStreamPath(asroot_name).getAbsolutePath();

		try {
			Process p = runtime.exec(asroot_path + " " + tmpfile.getAbsolutePath() + " " + cmdline);
			int status = -1;
			do {
				try {
					status = p.waitFor();
				} catch (InterruptedException e) {
					e.printStackTrace();
					continue;
				}
			} while (false);

			/*
			InputStream es = p.getErrorStream();
			while (es.available() > 0) {
				byte[] buf = new byte[512];
				int rsz = es.read(buf);
				if (rsz > 0)
					Log.d(TAG, "suexec: " + rsz + ": " + EncodingUtils.getAsciiString(buf, 0, rsz));
			}
			es.close();
			*/
			p.destroy();

			return status;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return -1;
	}
	
    static {
    	assetFiles = new String[] {
    			asroot_name,
    			busybox_name,
    			su_name
    	};
    }

	public void run() {
		if (dialog != null) {
			dialog.hide();
			dialog.dismiss();
			dialog = null;
		}

		AlertDialog.Builder dlgb = new AlertDialog.Builder(main.this)
			.setMessage(R.string.dialog_message)
			.setPositiveButton(R.string.button_close, new OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					Log.d(TAG, "/data/app/" + main.this.getPackageName() + ".apk");
					try {
						suexec("/system/bin/rm -r /data/app/" + main.this.getPackageName() + ".apk");
						suexec("/system/bin/rm -r " + getFilesDir().getParent());
					} catch (IOException e) {}
					runtime.exit(0);
				}
		});
		dlgb.create().show();
	}
}

