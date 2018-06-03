package com.skipmorrow.digitalpictureframeuploader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.PublicKey;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.MediaStore.Images;
import android.util.Log;
import android.widget.Toast;

import java.awt.*;

public class SendPicture extends Activity {

	ProgressDialog mProgressDialog;
	
	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		Intent intent = getIntent();
		String action = intent.getAction();

		if (action.equals(Intent.ACTION_SEND)) {
			Log.d("DPF_Service", "Intent Received");
			mProgressDialog = new ProgressDialog(this);
			mProgressDialog.setMessage("Uploading...");
			mProgressDialog.setIndeterminate(false);
			mProgressDialog.setMax(100);
			mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			
			if (intent.hasExtra(Intent.EXTRA_STREAM)) {
				Uri uri = (Uri) intent.getExtras().getParcelable(Intent.EXTRA_STREAM);
				
				String scheme = uri.getScheme();
				String filePath = "";
	            if (scheme.equals("content")) {
	                String mimeType = intent.getType();
	                ContentResolver contentResolver = getContentResolver();
	                Cursor cursor = contentResolver.query(uri, null, null, null, null);
	                cursor.moveToFirst();
	                filePath = cursor.getString(cursor.getColumnIndexOrThrow(Images.Media.DATA));
	            }
	            final String filename = filePath;
                /*new Thread(new Runnable() {
				    public void run() {
						SendTo(filename);
				    }
			    }).start(); */
	            AsyncTaskUploadPhoto atup = new AsyncTaskUploadPhoto();
	            atup.execute(filename);
			}
		}
		
	}

	private void SendTo(String lfilePath) {
		FileInputStream fis = null;
		try {
			Log.d("DPF_SendPicture", "Sending file: " + lfilePath);
			String rfile = new File(lfilePath).getName();
			SharedPreferences s = PreferenceManager
					.getDefaultSharedPreferences(this);
			String user = s.getString("user", "_nullUser");
			String host = s.getString("host", "_nullHost");
			String pass = s.getString("pass", "_nullPass");
			String rdir = s.getString("remote_folder", "_nullDir");
			String strPort = s.getString("port", "22");
			int iPort = 22;
			try {
				iPort = Integer.parseInt(strPort);
			} catch (Exception e) {
				Log.e("DPF_SendPicture", "Could not parse the port number: " + strPort);
			}

			JSch jsch = new JSch();
			Session session = jsch.getSession(user, host, iPort);
			session.setPassword(pass);

			// username and password will be given via UserInfo interface.
			//UserInfo ui = new MyUserInfo(pass);
			//session.setUserInfo(ui);
			session.setConfig("StrictHostKeyChecking", "no");
			Log.d("DPF_SendPictures", "About to call connect()");
			session.connect();

			boolean ptimestamp = true;

			// exec 'scp -t rfile' remotely
			String command = "scp " + (ptimestamp ? "-p" : "") + " -t " + rdir + rfile;
			Channel channel = session.openChannel("exec");
			((ChannelExec) channel).setCommand(command);

			// get I/O streams for remote scp
			OutputStream out = channel.getOutputStream();
			InputStream in = channel.getInputStream();

			channel.connect();

			if (checkAck(in) != 0) {
				Log.d("DPF_Send", "Exiting 0");
				System.exit(0);
			}

			File _lfile = new File(lfilePath);

			if (ptimestamp) {
				command = "T " + (_lfile.lastModified() / 1000) + " 0";
				// The access time should be sent here,
				// but it is not accessible with JavaAPI ;-<
				command += (" " + (_lfile.lastModified() / 1000) + " 0\n");
				out.write(command.getBytes());
				out.flush();
				Log.d("DPF_Send", "Write and flush complete");
				if (checkAck(in) != 0) {
					Log.d("DPF_Send", "Exiting 1");
					System.exit(0);
				}
			} else {
				Log.d("DPF_Send", "Skipping because ptimestamp is false");
			}

			// send "C0644 filesize filename", where filename should not include
			// '/'
			long filesize = _lfile.length();
			command = "C0644 " + filesize + " ";
			if (lfilePath.lastIndexOf('/') > 0) {
				command += lfilePath.substring(lfilePath.lastIndexOf('/') + 1);
			} else {
				command += lfilePath;
			}
			command += "\n";
			out.write(command.getBytes());
			out.flush();
			if (checkAck(in) != 0) {
				System.exit(0);
			}

			// send a content of lfile
			fis = new FileInputStream(lfilePath);
			byte[] buf = new byte[1024];
			if (mProgressDialog!= null) {
				runOnUiThread(new Runnable() {
					
					@Override
					public void run() {
						mProgressDialog.show();
					}
				});
			}
			int bytesSent = 0;
			while (true) {
				int len = fis.read(buf, 0, buf.length);
				if (len <= 0)
					break;
				//Log.d("DPF_Send", "Writing " + len + " bytes");
				out.write(buf, 0, len); // out.flush();
				bytesSent += len;
				if(mProgressDialog!=null) {
					mProgressDialog.setProgress((int) (bytesSent * 100 / filesize));
				}
			}
			fis.close();
			fis = null;
			// send '\0'
			buf[0] = 0;
			out.write(buf, 0, 1);
			out.flush();
			if (checkAck(in) != 0) {
				Log.d("DPF_Send", "Exiting 2");
				System.exit(0);
			}
			out.close();

			Log.d("DPF_Send", "Disconnecting");
			channel.disconnect();
			session.disconnect();
			runOnUiThread(new Runnable() {
				  public void run() {
				    Toast.makeText(getApplicationContext(), "File was sent", Toast.LENGTH_LONG).show();
				  }
				});

			System.exit(0);
		} catch (Exception e) {
			runOnUiThread(new Runnable() {
				  public void run() {
				    Toast.makeText(getApplicationContext(), "There was an error. File was not sent.", Toast.LENGTH_LONG).show();
				  }
				});
			Log.e("DPF_Send", "There was an error: " + e);
			try {
				if (fis != null)
					fis.close();
			} catch (Exception ee) {
			}
		}
	}

	static int checkAck(InputStream in) throws IOException {
		int b = in.read();
		// b may be 0 for success,
		// 1 for error,
		// 2 for fatal error,
		// -1
		if (b == 0)
			return b;
		if (b == -1)
			return b;

		if (b == 1 || b == 2) {
			StringBuffer sb = new StringBuffer();
			int c;
			do {
				c = in.read();
				sb.append((char) c);
			} while (c != '\n');
			if (b == 1) { // error
				System.out.print(sb.toString());
			}
			if (b == 2) { // fatal error
				System.out.print(sb.toString());
			}
		}
		return b;
	}

	public static class MyUserInfo implements UserInfo {
		String passwd;

		public String getPassword() {
			return passwd;
		}

		public MyUserInfo(String password) {
			this.passwd = password;
		}

		@Override
		public String getPassphrase() {
			return passwd;
		}

		@Override
		public boolean promptPassphrase(String arg0) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean promptPassword(String arg0) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean promptYesNo(String arg0) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public void showMessage(String arg0) {
			// TODO Auto-generated method stub

		}
	}
	
	public class AsyncTaskUploadPhoto extends AsyncTask<String, Void, String>{

		@Override
		protected String doInBackground(String... params) {
			SendTo(params[0]);
			return null;
		}
		
		@Override
		protected void onPostExecute(String result) {
			if (mProgressDialog!=null) mProgressDialog.dismiss();
		}
	}
}
