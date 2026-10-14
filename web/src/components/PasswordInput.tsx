'use client';

import { forwardRef, useState } from 'react';
import { generatePassword } from '@/lib/generatePassword';

/**
 * Password field for admin forms: show/hide toggle plus "Sinh mật khẩu"
 * (fills a random password and reveals it so the admin can copy it).
 */
export const PasswordInput = forwardRef<
  HTMLInputElement,
  { value: string; onChange: (value: string) => void; className: string; ariaLabel?: string }
>(function PasswordInput({ value, onChange, className, ariaLabel }, ref) {
  const [visible, setVisible] = useState(false);
  return (
    <div className="flex items-center gap-2">
      <input
        ref={ref}
        type={visible ? 'text' : 'password'}
        value={value}
        aria-label={ariaLabel}
        onChange={(e) => onChange(e.target.value)}
        className={className}
        autoComplete="new-password"
      />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        className="shrink-0 cursor-pointer text-xs font-semibold text-gray-500 hover:text-[#0D2546]"
      >
        {visible ? 'Ẩn' : 'Hiện'}
      </button>
      <button
        type="button"
        onClick={() => {
          onChange(generatePassword());
          setVisible(true);
        }}
        className="shrink-0 cursor-pointer whitespace-nowrap text-xs font-semibold text-[#0D2546] hover:underline"
      >
        Sinh mật khẩu
      </button>
    </div>
  );
});
